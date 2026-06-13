package com.oconeco.spring_search_tempo.web.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.domain.JobRun
import com.oconeco.spring_search_tempo.base.domain.MirrorConfig
import com.oconeco.spring_search_tempo.base.domain.MirrorError
import com.oconeco.spring_search_tempo.base.domain.MirroredMessage
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.domain.MirrorFolderProgress
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorConfigRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorErrorRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorFolderProgressRepository
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import io.restassured.RestAssured
import io.restassured.http.ContentType
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import java.time.OffsetDateTime
import java.util.Date
import java.util.Properties

/**
 * Dashboard + retry + CSV-export coverage for the mirror progress page
 * (issue #26). The progress page itself is driven entirely from the DB,
 * so most tests construct fixture rows directly; the retry test uses
 * GreenMail so the retry endpoint actually attempts an APPEND against
 * a live IMAP destination.
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DirtiesContext
@DisplayName("Mirror progress dashboard, retry, CSV export (issue #26)")
class MirrorProgressViewIT : BaseIT() {

    companion object {
        // Dynamic ports (issue #106): hardcoded ports collided across
        // GreenMail-backed tests sharing the same JVM after Spring
        // tear-down. Read the live ports via [srcPort] / [dstPort]
        // accessors — each `.reset()` rebinds to a fresh OS port.
        private const val SRC_EMAIL = "old-progress@oconeco.com"
        private const val DST_EMAIL = "new-progress@oconeco.com"
        private const val MAIL_PASSWORD = "secret"

        private lateinit var srcServer: GreenMail
        private lateinit var dstServer: GreenMail
        private val srcPort: Int get() = srcServer.imap.port
        private val dstPort: Int get() = dstServer.imap.port

        @JvmStatic
        @BeforeAll
        fun startServers() {
            srcServer = GreenMail(ServerSetup(0, null, "imap")).also { it.start() }
            dstServer = GreenMail(ServerSetup(0, null, "imap")).also { it.start() }
        }

        @JvmStatic
        @AfterAll
        fun stopServers() {
            try { srcServer.stop() } catch (_: Exception) {}
            try { dstServer.stop() } catch (_: Exception) {}
        }
    }

    @Autowired lateinit var mirrorConfigRepository: MirrorConfigRepository
    @Autowired lateinit var mirroredMessageRepository: MirroredMessageRepository
    @Autowired lateinit var mirrorErrorRepository: MirrorErrorRepository
    @Autowired lateinit var mirrorFolderProgressRepository: MirrorFolderProgressRepository
    @Autowired lateinit var jobRunRepository: JobRunRepository
    @Autowired lateinit var emailAccountRepository: EmailAccountRepository
    @Autowired lateinit var emailAccountService: EmailAccountService
    @Autowired lateinit var objectMapper: ObjectMapper

    private var configId: Long = 0L
    private var jobRunId: Long = 0L
    private var sourceAccountId: Long = 0L
    private var destAccountId: Long = 0L

    @BeforeEach
    fun setUp() {
        srcServer.reset()
        dstServer.reset()
        srcServer.setUser(SRC_EMAIL, SRC_EMAIL, MAIL_PASSWORD)
        dstServer.setUser(DST_EMAIL, DST_EMAIL, MAIL_PASSWORD)

        mirrorErrorRepository.deleteAll()
        mirrorFolderProgressRepository.deleteAll()
        mirroredMessageRepository.deleteAll()
        jobRunRepository.deleteAll()
        mirrorConfigRepository.deleteAll()
        emailAccountRepository.deleteAll()

        sourceAccountId = persistAccount(SRC_EMAIL, srcPort)
        destAccountId = persistAccount(DST_EMAIL, dstPort)

        configId = mirrorConfigRepository.save(
            MirrorConfig().apply {
                uri = "mirror://progress-test-${System.nanoTime()}"
                name = "progress-test mirror"
                this.sourceAccountId = this@MirrorProgressViewIT.sourceAccountId
                this.destAccountId = this@MirrorProgressViewIT.destAccountId
                enabled = true
                appendRateLimitPerSecond = null
                folderMappings = objectMapper.writeValueAsString(
                    listOf(
                        FolderMapping(source = "INBOX", dest = "INBOX", enabled = true),
                        FolderMapping(source = "Sent", dest = "Sent", enabled = true)
                    )
                )
            }
        ).id!!

        // Resolve the JPA entity to attach to JobRun.mirrorConfig.
        val mirrorEntity = mirrorConfigRepository.findById(configId).orElseThrow()
        jobRunId = jobRunRepository.save(
            JobRun().apply {
                this.mirrorConfig = mirrorEntity
                this.jobName = "mirrorJob"
                this.label = mirrorEntity.name
                this.startTime = OffsetDateTime.now().minusMinutes(2)
                this.runStatus = RunStatus.RUNNING
                this.status = Status.IN_PROGRESS
                this.uri = "job-run:mirrorJob:${configId}:${System.nanoTime()}"
                this.version = 0L
                this.processedCount = 0L
            }
        ).id!!
    }

    @AfterEach
    fun tearDown() {
        mirrorErrorRepository.deleteAll()
        mirrorFolderProgressRepository.deleteAll()
        mirroredMessageRepository.deleteAll()
        jobRunRepository.deleteAll()
        mirrorConfigRepository.deleteAll()
        emailAccountRepository.deleteAll()
    }

    @Test
    @DisplayName("progress page renders per-folder counts and HTMX auto-refresh during active run")
    fun progressPageRendersDuringActiveRun() {
        seedMirrored("INBOX", 3)
        seedMirrored("Sent", 2)
        seedError("INBOX", uid = 99L, retryable = true, reason = "IMAP appended timed out")
        // Reader has opened INBOX (totalConsidered=10) and finished it,
        // and is currently mid-flight on Sent (totalConsidered=5).
        seedFolderProgress("INBOX", "INBOX", totalConsidered = 10L, complete = true)
        seedFolderProgress("Sent", "Sent", totalConsidered = 5L, complete = false)

        val body = RestAssured
            .given()
            .accept(ContentType.HTML)
            .`when`()
            .get("/emailMirrors/$configId/runs/$jobRunId")
            .then()
            .statusCode(200)
            .extract().asString()

        assertThat(body).contains("INBOX")
        assertThat(body).contains("Sent")
        // Per-folder denominators come from MirrorFolderProgress, not the
        // misleading mirrored/(mirrored+failed) ratio.
        assertThat(body).contains("3 / 10 messages")
        assertThat(body).contains("2 / 5 messages")
        // Status line: folders complete / folders total.
        assertThat(body).contains("Folders: 1 / 2 complete")
        assertThat(body).contains("1 in flight")
        // Status badge per folder.
        assertThat(body).contains("complete")
        assertThat(body).contains("in flight")
        // The misleading "% complete" column is gone.
        assertThat(body).doesNotContain("% complete")
        // Error log row visible.
        assertThat(body).contains("IMAP appended timed out")

        // Auto-refresh trigger is on the progress fragment root; asserting on
        // the full page is unreliable because the layout's nav job-indicator
        // also carries `hx-trigger="every 5s"`. Request the HTMX fragment.
        val fragment = RestAssured
            .given()
            .accept(ContentType.HTML)
            .header("HX-Request", "true")
            .`when`()
            .get("/emailMirrors/$configId/runs/$jobRunId")
            .then()
            .statusCode(200)
            .extract().asString()
        assertThat(fragment).contains("hx-trigger=\"every 5s\"")
    }

    @Test
    @DisplayName("folder without a progress row renders count-only, not a fake denominator")
    fun folderWithoutProgressRowOmitsDenominator() {
        // No MirrorFolderProgress rows seeded — i.e. the reader hasn't
        // opened either folder yet. The dashboard must NOT fabricate a
        // denominator from mirrored + failed (the issue #33 regression).
        seedMirrored("INBOX", 4)
        seedError("INBOX", uid = 99L, retryable = true, reason = "transient")

        val body = RestAssured
            .given()
            .accept(ContentType.HTML)
            .`when`()
            .get("/emailMirrors/$configId/runs/$jobRunId")
            .then()
            .statusCode(200)
            .extract().asString()

        // Count-only rendering when no denominator is available.
        assertThat(body).contains("4 messages")
        // Specifically: do NOT show "4 / 5 messages" (the old behaviour
        // that lied during the run by treating mirrored+failed as total).
        assertThat(body).doesNotContain("4 / 5 messages")
        // Both folders are still queued (no open recorded).
        assertThat(body).contains("Folders: 0 / 2 complete")
        assertThat(body).contains("queued")
    }

    @Test
    @DisplayName("auto-refresh trigger disappears once the run reaches COMPLETED")
    fun autoRefreshStopsOnCompletion() {
        seedMirrored("INBOX", 1)

        val running = jobRunRepository.findById(jobRunId).orElseThrow()
        running.runStatus = RunStatus.COMPLETED
        running.finishTime = OffsetDateTime.now()
        running.status = Status.CURRENT
        jobRunRepository.save(running)

        // Request the HTMX fragment only — the full page always includes the
        // layout's nav job-indicator (`hx-trigger="every 5s"`), which would
        // make the "doesNotContain" assertion impossible on the wrapped page.
        val fragment = RestAssured
            .given()
            .accept(ContentType.HTML)
            .header("HX-Request", "true")
            .`when`()
            .get("/emailMirrors/$configId/runs/$jobRunId")
            .then()
            .statusCode(200)
            .extract().asString()

        assertThat(fragment).doesNotContain("hx-trigger=\"every 5s\"")
        assertThat(fragment).contains("Completed")
    }

    @Test
    @DisplayName("retry-failed re-queues only retryable=true errors and succeeds when destination accepts the APPEND")
    fun retryableErrorsCanBeReQueued() {
        // Real source message; the retry path will FETCH this UID then APPEND
        // it to the destination so the success branch runs end-to-end.
        deliverToSource("retry-me", "body of retryable failure", "<retry-1@oconeco.com>")
        val srcUid = listSourceUids("INBOX").first()
        ensureDestFolder("INBOX")

        // Retryable error pointing at the real UID — retry should succeed.
        seedError("INBOX", uid = srcUid, retryable = true, reason = "transient IMAP timeout")
        // Permanent error pointing at a missing UID — must NOT be touched.
        seedError("INBOX", uid = 99_999L, retryable = false, reason = "AUTH failed on dest")

        val response = RestAssured
            .given()
            .accept(ContentType.JSON)
            .`when`()
            .post("/api/email/mirrors/$configId/retry-failed")
            .then()
            .statusCode(200)
            .extract().asString()

        assertThat(response).contains("\"attempted\":1")
        assertThat(response).contains("\"succeeded\":1")
        assertThat(response).contains("\"stillFailing\":0")

        // Retryable error row was deleted on success; permanent error is left in place.
        val remaining = mirrorErrorRepository.findByMirrorConfigIdOrderByOccurredAtDesc(
            configId, org.springframework.data.domain.PageRequest.of(0, 10)
        ).content
        assertThat(remaining).hasSize(1)
        assertThat(remaining[0].retryable).isFalse
        // Audit row inserted by ImapMirrorService on the successful retry.
        assertThat(mirroredMessageRepository.countByMirrorConfigId(configId)).isEqualTo(1)
    }

    @Test
    @DisplayName("CSV export streams error log with the expected header + one row per error")
    fun csvExportStreamsErrorLog() {
        seedError("INBOX", uid = 11L, retryable = true, reason = "tmp")
        seedError("Sent", uid = 12L, retryable = false, reason = "permanent")
        seedError("INBOX", uid = 13L, retryable = true, reason = "needs, escaping")

        val response = RestAssured
            .given()
            .`when`()
            .get("/emailMirrors/$configId/runs/$jobRunId/errors.csv")
            .then()
            .statusCode(200)
            .extract()

        val body = response.asString()
        assertThat(response.contentType()).startsWith("text/csv")
        val lines = body.trim().lines()
        // header + 3 error rows
        assertThat(lines).hasSize(4)
        assertThat(lines[0]).isEqualTo("occurred_at,source_folder,source_uid,dest_folder,message_id,retryable,reason")
        assertThat(lines.drop(1)).anyMatch { it.contains(",INBOX,11,") && it.endsWith(",true,tmp") }
        assertThat(lines.drop(1)).anyMatch { it.contains(",Sent,12,") && it.endsWith(",false,permanent") }
        // CSV cell with a comma must be quoted.
        assertThat(lines.drop(1)).anyMatch { it.contains("\"needs, escaping\"") }
    }

    @Test
    @DisplayName("/emailMirrors index page links to the most recent run")
    fun listPageLinksToLatestRun() {
        seedMirrored("INBOX", 1)

        val body = RestAssured
            .given()
            .accept(ContentType.HTML)
            .`when`()
            .get("/emailMirrors")
            .then()
            .statusCode(200)
            .extract().asString()

        assertThat(body).contains("/emailMirrors/$configId/runs/$jobRunId")
        assertThat(body).contains("Running")
    }

    // ---- fixture helpers -------------------------------------------------

    private fun seedMirrored(folder: String, count: Int) {
        repeat(count) { i ->
            mirroredMessageRepository.save(
                MirroredMessage().apply {
                    mirrorConfigId = this@MirrorProgressViewIT.configId
                    sourceFolder = folder
                    sourceUid = (i + 1).toLong()
                    destFolder = folder
                    destUid = null
                    messageId = "<seed-${folder.lowercase()}-$i@oconeco.com>"
                    mirroredAt = OffsetDateTime.now()
                }
            )
        }
    }

    private fun seedFolderProgress(
        source: String,
        dest: String,
        totalConsidered: Long,
        complete: Boolean
    ) {
        mirrorFolderProgressRepository.save(
            MirrorFolderProgress().apply {
                this.mirrorConfigId = this@MirrorProgressViewIT.configId
                this.jobRunId = this@MirrorProgressViewIT.jobRunId
                this.sourceFolder = source
                this.destFolder = dest
                this.totalConsidered = totalConsidered
                this.openedAt = OffsetDateTime.now()
                this.completedAt = if (complete) OffsetDateTime.now() else null
            }
        )
    }

    private fun seedError(folder: String, uid: Long, retryable: Boolean, reason: String) {
        mirrorErrorRepository.save(
            MirrorError().apply {
                this.mirrorConfigId = this@MirrorProgressViewIT.configId
                this.jobRunId = this@MirrorProgressViewIT.jobRunId
                this.sourceFolder = folder
                this.sourceUid = uid
                this.destFolder = folder
                this.reason = reason
                this.retryable = retryable
                this.occurredAt = OffsetDateTime.now()
            }
        )
    }

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
        emailAccountService.setPassword(id, MAIL_PASSWORD)
        return id
    }

    private fun deliverToSource(subject: String, body: String, messageId: String, folderName: String = "INBOX") {
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
        mailbox.appendMessage(msg, Flags(), Date())
    }

    private fun ensureDestFolder(folderName: String) {
        val user = dstServer.managers.userManager.getUser(DST_EMAIL)
        if (dstServer.managers.imapHostManager.getFolder(user, folderName) == null) {
            dstServer.managers.imapHostManager.createMailbox(user, folderName)
        }
    }

    private fun listSourceUids(folderName: String): List<Long> {
        val store = Session.getInstance(Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", srcPort.toString())
        }).getStore("imap")
        store.connect("127.0.0.1", srcPort, SRC_EMAIL, MAIL_PASSWORD)
        try {
            val folder = store.getFolder(folderName) as com.sun.mail.imap.IMAPFolder
            folder.open(jakarta.mail.Folder.READ_ONLY)
            try {
                return folder.messages.map { folder.getUID(it) }.sorted()
            } finally {
                runCatching { folder.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
    }
}
