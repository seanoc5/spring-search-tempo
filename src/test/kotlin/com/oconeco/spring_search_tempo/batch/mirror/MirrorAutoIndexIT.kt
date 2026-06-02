package com.oconeco.spring_search_tempo.batch.mirror

import com.fasterxml.jackson.databind.ObjectMapper
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.domain.MirrorConfig
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorCheckpointRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorConfigRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorFolderProgressRepository
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import com.oconeco.spring_search_tempo.base.service.mirror.MirrorRateLimiter
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import jakarta.mail.Flags
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import com.oconeco.spring_search_tempo.batch.emailcrawl.ParallelizationConfig
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.Date
import java.util.Properties

/**
 * Verifies the mirror → auto-index handoff (issue #35).
 *
 * Strategy: stand up the full Spring context with `app.mirror.auto-index=true`,
 * run a real MirrorJob against a GreenMail source/destination pair, and assert
 * that `EmailCrawlOrchestrator.runQuickSyncForAccount(destAccountId)` is invoked
 * exactly once after the mirror reaches `BatchStatus.COMPLETED`.
 *
 * The orchestrator itself is mocked with `@MockitoBean` so this IT focuses on
 * the new wiring (event publication + listener routing) without re-running the
 * 6-pass email pipeline that has its own dedicated coverage.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DirtiesContext
@TestPropertySource(properties = ["app.mirror.auto-index=true"])
@DisplayName("MirrorJob auto-index → email quick-sync handoff (issue #35)")
class MirrorAutoIndexIT : BaseIT() {

    companion object {
        private const val SRC_PORT = 3247
        private const val DST_PORT = 3249

        private const val SRC_EMAIL = "src@oconeco.com"
        private const val DST_EMAIL = "dst@oconeco.com"
        private const val PASSWORD = "secret"

        private lateinit var srcServer: GreenMail
        private lateinit var dstServer: GreenMail

        @JvmStatic
        @BeforeAll
        fun startServers() {
            srcServer = GreenMail(ServerSetup(SRC_PORT, null, "imap")).also { it.start() }
            dstServer = GreenMail(ServerSetup(DST_PORT, null, "imap")).also { it.start() }
        }

        @JvmStatic
        @AfterAll
        fun stopServers() {
            try { srcServer.stop() } catch (_: Exception) {}
            try { dstServer.stop() } catch (_: Exception) {}
        }
    }

    @Autowired lateinit var jobRepository: JobRepository
    @Autowired lateinit var mirrorJob: Job
    @Autowired lateinit var mirrorConfigRepository: MirrorConfigRepository
    @Autowired lateinit var mirroredMessageRepository: MirroredMessageRepository
    @Autowired lateinit var mirrorCheckpointRepository: MirrorCheckpointRepository
    @Autowired lateinit var mirrorFolderProgressRepository: MirrorFolderProgressRepository
    @Autowired lateinit var jobRunRepository: com.oconeco.spring_search_tempo.base.repos.JobRunRepository
    @Autowired lateinit var emailAccountRepository: EmailAccountRepository
    @Autowired lateinit var emailAccountService: EmailAccountService
    @Autowired lateinit var rateLimiter: MirrorRateLimiter
    @Autowired lateinit var objectMapper: ObjectMapper

    // Replace the real orchestrator so we don't fan out into the 6-pass
    // email pipeline (which already has its own tests). The mock lets us
    // assert that the listener invoked it with the destination accountId.
    @MockitoBean
    lateinit var emailCrawlOrchestrator: EmailCrawlOrchestrator

    private val syncLauncher: TaskExecutorJobLauncher by lazy {
        TaskExecutorJobLauncher().apply {
            setJobRepository(jobRepository)
            setTaskExecutor(SyncTaskExecutor())
            afterPropertiesSet()
        }
    }

    private var configId: Long = 0
    private var sourceAccountId: Long = 0
    private var destAccountId: Long = 0

    @BeforeEach
    fun setUp() {
        srcServer.reset()
        dstServer.reset()
        srcServer.setUser(SRC_EMAIL, SRC_EMAIL, PASSWORD)
        dstServer.setUser(DST_EMAIL, DST_EMAIL, PASSWORD)

        mirrorCheckpointRepository.deleteAll()
        mirrorFolderProgressRepository.deleteAll()
        mirroredMessageRepository.deleteAll()
        jobRunRepository.deleteAll()
        mirrorConfigRepository.deleteAll()
        emailAccountRepository.deleteAll()
        rateLimiter.reset()

        sourceAccountId = persistAccount(SRC_EMAIL, SRC_PORT)
        destAccountId = persistAccount(DST_EMAIL, DST_PORT)

        configId = mirrorConfigRepository.save(
            MirrorConfig().apply {
                uri = "mirror://auto-index-test-${System.nanoTime()}"
                name = "mirror auto-index test"
                this.sourceAccountId = this@MirrorAutoIndexIT.sourceAccountId
                this.destAccountId = this@MirrorAutoIndexIT.destAccountId
                enabled = true
                appendRateLimitPerSecond = null
                folderMappings = objectMapper.writeValueAsString(
                    listOf(FolderMapping(source = "INBOX", dest = "INBOX", enabled = true))
                )
            }
        ).id!!

        // The listener swallows downstream exceptions (including the NPE
        // it hits when dereferencing a default-null mocked JobExecution),
        // so we don't need to stub a return value to observe the call.
    }

    @AfterEach
    fun tearDown() {
        mirrorCheckpointRepository.deleteAll()
        mirrorFolderProgressRepository.deleteAll()
        mirroredMessageRepository.deleteAll()
        jobRunRepository.deleteAll()
        mirrorConfigRepository.deleteAll()
        emailAccountRepository.deleteAll()
    }

    @Test
    @DisplayName("mirror COMPLETED → publishes event → triggers quick-sync for destAccountId")
    fun triggersQuickSyncAfterCompletedMirror() {
        repeat(3) { i ->
            deliverToSource(
                subject = "auto-index $i",
                body = "auto-index body $i",
                messageId = "<auto-index-$i@oconeco.com>",
                internalDate = Date()
            )
        }

        val exec = syncLauncher.run(mirrorJob, paramsFor(configId))

        assertThat(exec.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(mirroredMessageRepository.countByMirrorConfigId(configId)).isEqualTo(3)

        val captor = ArgumentCaptor.forClass(Long::class.javaObjectType)
        verify(emailCrawlOrchestrator, times(1)).runQuickSyncForAccount(
            captor.capture(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyInt(),
            anyParallelizationConfig()
        )
        assertThat(captor.value).isEqualTo(destAccountId)
    }

    @Test
    @DisplayName("no mirrored messages → no quick-sync trigger (zero-write short-circuit)")
    fun skipsQuickSyncWhenNothingMirrored() {
        // Empty source mailbox — mirror completes with zero writes.
        val exec = syncLauncher.run(mirrorJob, paramsFor(configId))

        assertThat(exec.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(mirroredMessageRepository.countByMirrorConfigId(configId)).isEqualTo(0)

        verify(emailCrawlOrchestrator, org.mockito.Mockito.never()).runQuickSyncForAccount(
            org.mockito.Mockito.anyLong(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyInt(),
            anyParallelizationConfig()
        )
    }

    /**
     * Mockito's `ArgumentMatchers.any(Class<T>)` returns `null`, which trips
     * Kotlin's nullability check when the parameter type is non-null
     * (`ParallelizationConfig`). Register the matcher with Mockito but
     * return a real instance so Kotlin's call-site null guard is happy.
     */
    private fun anyParallelizationConfig(): ParallelizationConfig {
        ArgumentMatchers.any(ParallelizationConfig::class.java)
        return ParallelizationConfig()
    }

    // ---- helpers ---------------------------------------------------------

    private fun paramsFor(mirrorConfigId: Long) = JobParametersBuilder()
        .addLong("mirrorConfigId", mirrorConfigId)
        .addLong("timestamp", System.currentTimeMillis())
        .toJobParameters()

    private fun persistAccount(email: String, port: Int): Long {
        val account = EmailAccount().apply {
            this.email = email
            this.uri = "email://$email-${System.nanoTime()}"
            this.provider = EmailProvider.GENERIC_IMAP
            this.imapHost = "127.0.0.1"
            this.imapPort = port
            this.useSsl = false
            this.enabled = true
            this.version = 1L
        }
        val id = emailAccountRepository.save(account).id!!
        emailAccountService.setPassword(id, PASSWORD)
        return id
    }

    private fun deliverToSource(
        subject: String,
        body: String,
        messageId: String,
        internalDate: Date
    ) {
        val msg = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress("sender@example.com"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, SRC_EMAIL)
            this.subject = subject
            setText(body)
            saveChanges()
            setHeader("Message-ID", messageId)
        }
        val user = srcServer.managers.userManager.getUser(SRC_EMAIL)
        val mailbox = srcServer.managers.imapHostManager.getInbox(user)
        mailbox.appendMessage(msg, Flags(), internalDate)
    }
}
