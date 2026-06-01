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
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import com.oconeco.spring_search_tempo.base.service.MirrorCheckpointService
import com.oconeco.spring_search_tempo.base.service.mirror.MirrorRateLimiter
import jakarta.mail.Flags
import jakarta.mail.Folder
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
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import java.time.OffsetDateTime
import java.util.Date
import java.util.Properties

/**
 * End-to-end MirrorJob test (issue #24).
 *
 * Spins up two GreenMail IMAP servers (source on 3147, destination on 3149)
 * and exercises the full reader → processor → writer chain through the real
 * Spring Batch wiring. The job copies every UID in every enabled folder
 * mapping, persists `MirroredMessage` rows for each, and clears the
 * `MirrorCheckpoint` on successful completion.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DirtiesContext
@DisplayName("MirrorJob — Spring Batch end-to-end + checkpoint resume (issue #24)")
class MirrorJobIT : BaseIT() {

    companion object {
        private const val SRC_PORT = 3147
        private const val DST_PORT = 3149

        private const val SRC_EMAIL = "old@oconeco.com"
        private const val DST_EMAIL = "new@oconeco.com"
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

    // The auto-configured `jobLauncher` is post-processed in
    // BatchTaskExecutorConfig to run async (so HTTP handlers can return
    // immediately). In tests we want a deterministic synchronous run so
    // assertions see the final BatchStatus — this wraps the same
    // jobRepository in a fresh TaskExecutorJobLauncher with a
    // SyncTaskExecutor.
    private val syncLauncher: TaskExecutorJobLauncher by lazy {
        TaskExecutorJobLauncher().apply {
            setJobRepository(jobRepository)
            setTaskExecutor(SyncTaskExecutor())
            afterPropertiesSet()
        }
    }

    private fun runJob(params: JobParameters): JobExecution =
        syncLauncher.run(mirrorJob, params)
    @Autowired lateinit var mirrorJob: Job
    @Autowired lateinit var mirrorConfigRepository: MirrorConfigRepository
    @Autowired lateinit var mirroredMessageRepository: MirroredMessageRepository
    @Autowired lateinit var mirrorCheckpointRepository: MirrorCheckpointRepository
    @Autowired lateinit var mirrorCheckpointService: MirrorCheckpointService
    @Autowired lateinit var jobRunRepository: com.oconeco.spring_search_tempo.base.repos.JobRunRepository
    @Autowired lateinit var emailAccountRepository: EmailAccountRepository
    @Autowired lateinit var emailAccountService: EmailAccountService
    @Autowired lateinit var rateLimiter: MirrorRateLimiter
    @Autowired lateinit var objectMapper: ObjectMapper

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
        mirroredMessageRepository.deleteAll()
        jobRunRepository.deleteAll()
        mirrorConfigRepository.deleteAll()
        emailAccountRepository.deleteAll()
        rateLimiter.reset()

        sourceAccountId = persistAccount(SRC_EMAIL, SRC_PORT)
        destAccountId = persistAccount(DST_EMAIL, DST_PORT)


        configId = mirrorConfigRepository.save(
            MirrorConfig().apply {
                uri = "mirror://job-test-${System.nanoTime()}"
                name = "mirror job test"
                this.sourceAccountId = this@MirrorJobIT.sourceAccountId
                this.destAccountId = this@MirrorJobIT.destAccountId
                enabled = true
                appendRateLimitPerSecond = null
                folderMappings = objectMapper.writeValueAsString(
                    listOf(
                        FolderMapping(source = "INBOX", dest = "INBOX", enabled = true)
                    )
                )
            }
        ).id!!
    }

    @AfterEach
    fun tearDown() {
        mirrorCheckpointRepository.deleteAll()
        mirroredMessageRepository.deleteAll()
        jobRunRepository.deleteAll()
        mirrorConfigRepository.deleteAll()
        emailAccountRepository.deleteAll()
    }

    @Test
    @DisplayName("runs end-to-end over a single folder; persists MirroredMessage rows and clears checkpoint")
    fun runsEndToEndForSingleFolder() {
        repeat(5) { i ->
            deliverToSource(
                subject = "subject $i",
                body = "body $i",
                messageId = "<mid-$i@oconeco.com>",
                internalDate = Date()
            )
        }

        val exec = runJob(paramsFor(configId))

        assertThat(exec.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(mirroredMessageRepository.countByMirrorConfigId(configId)).isEqualTo(5)
        assertThat(destMessageCount("INBOX")).isEqualTo(5)
        // Checkpoint is cleared on successful completion.
        assertThat(mirrorCheckpointService.find(configId)).isNull()
        // lastRunCompletedAt was stamped.
        val refreshed = mirrorConfigRepository.findById(configId).orElseThrow()
        assertThat(refreshed.lastRunCompletedAt).isNotNull
    }

    @Test
    @DisplayName("runs end-to-end across two folders; both folders fully copied")
    fun runsEndToEndAcrossTwoFolders() {
        // Set up a second mapping for the "Sent" folder.
        val cfg = mirrorConfigRepository.findById(configId).orElseThrow()
        cfg.folderMappings = objectMapper.writeValueAsString(
            listOf(
                FolderMapping(source = "INBOX", dest = "INBOX", enabled = true),
                FolderMapping(source = "Sent", dest = "Sent", enabled = true)
            )
        )
        mirrorConfigRepository.save(cfg)

        repeat(3) { i ->
            deliverToSource(
                folderName = "INBOX",
                subject = "inbox $i",
                body = "inbox body $i",
                messageId = "<inbox-$i@oconeco.com>",
                internalDate = Date()
            )
        }
        repeat(2) { i ->
            deliverToSource(
                folderName = "Sent",
                subject = "sent $i",
                body = "sent body $i",
                messageId = "<sent-$i@oconeco.com>",
                internalDate = Date()
            )
        }
        ensureDestFolder("Sent")

        val exec = runJob(paramsFor(configId))

        assertThat(exec.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(mirroredMessageRepository.countByMirrorConfigId(configId)).isEqualTo(5)
        assertThat(destMessageCount("INBOX")).isEqualTo(3)
        assertThat(destMessageCount("Sent")).isEqualTo(2)
        assertThat(mirrorCheckpointService.find(configId)).isNull()
    }

    @Test
    @DisplayName("resumes from checkpoint after mid-folder interruption; no duplicates on dest")
    fun resumesFromCheckpoint() {
        // Pre-mirror 3 of the 5 messages and write a checkpoint that pretends
        // the job was interrupted right after the third message. The reader
        // must skip UIDs ≤ checkpoint and ImapMirrorService dedup catches any
        // overlap.
        repeat(5) { i ->
            deliverToSource(
                subject = "resume $i",
                body = "resume body $i",
                messageId = "<resume-$i@oconeco.com>",
                internalDate = Date()
            )
        }
        // Simulate an interrupted prior run: pretend the first three were
        // already mirrored (we record audit rows + a checkpoint at UID 3).
        val srcUids = listSourceUids("INBOX")
        assertThat(srcUids).hasSize(5)
        val midUid = srcUids[2]
        for (i in 0..2) {
            mirroredMessageRepository.save(
                com.oconeco.spring_search_tempo.base.domain.MirroredMessage().apply {
                    mirrorConfigId = configId
                    sourceFolder = "INBOX"
                    sourceUid = srcUids[i]
                    destFolder = "INBOX"
                    destUid = null
                    messageId = "<resume-$i@oconeco.com>"
                    mirroredAt = OffsetDateTime.now()
                }
            )
        }
        mirrorCheckpointService.upsert(configId, "INBOX", midUid)

        val exec = runJob(paramsFor(configId))

        assertThat(exec.status).isEqualTo(BatchStatus.COMPLETED)
        // Resume: the two remaining UIDs got APPENDed (only those two end up
        // on the destination); the pre-seeded audit rows are still in place.
        assertThat(mirroredMessageRepository.countByMirrorConfigId(configId)).isEqualTo(5)
        assertThat(destMessageCount("INBOX")).isEqualTo(2)
        // No duplicates on the destination — the resume marker plus the
        // audit-row pre-filter prevented re-APPENDing the first three.
        assertThat(mirrorCheckpointService.find(configId)).isNull()
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
        internalDate: Date,
        folderName: String = "INBOX"
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
        val mailbox = if (folderName.equals("INBOX", ignoreCase = true)) {
            srcServer.managers.imapHostManager.getInbox(user)
        } else {
            srcServer.managers.imapHostManager.getFolder(user, folderName)
                ?: srcServer.managers.imapHostManager.createMailbox(user, folderName)
        }
        mailbox.appendMessage(msg, Flags(), internalDate)
    }

    private fun ensureDestFolder(folderName: String) {
        val user = dstServer.managers.userManager.getUser(DST_EMAIL)
        if (dstServer.managers.imapHostManager.getFolder(user, folderName) == null) {
            dstServer.managers.imapHostManager.createMailbox(user, folderName)
        }
    }

    private fun destMessageCount(folderName: String): Int =
        openFolderReadOnly(DST_PORT, DST_EMAIL, folderName) { it.messageCount }

    private fun listSourceUids(folderName: String): List<Long> {
        return openFolderReadOnly(SRC_PORT, SRC_EMAIL, folderName) { folder ->
            val imap = folder as com.sun.mail.imap.IMAPFolder
            folder.messages.map { imap.getUID(it) }.sorted()
        }
    }

    private fun <T> openFolderReadOnly(port: Int, user: String, folderName: String, block: (Folder) -> T): T {
        val store = Session.getInstance(Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", port.toString())
        }).getStore("imap")
        store.connect("127.0.0.1", port, user, PASSWORD)
        try {
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            try {
                return block(folder)
            } finally {
                runCatching { folder.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
    }
}
