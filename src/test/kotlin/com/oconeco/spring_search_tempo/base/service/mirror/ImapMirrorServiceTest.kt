package com.oconeco.spring_search_tempo.base.service.mirror

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.domain.MirrorConfig
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorConfigRepository
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.Date
import java.util.Properties

/**
 * Tests for `ImapMirrorService` (issue #23).
 *
 * Uses two GreenMail IMAP servers (source on 3143, destination on 3145)
 * so a single test can drive a real lossless copy end-to-end. The shared
 * `EmailTestFixtures` only exposes a single-server helper, so this test
 * spins up its own dual-server pair directly — the second mirror ticket
 * (#24) can extract a `DualGreenMail` fixture if the shape recurs.
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    // BaseIT autowires `@LocalServerPort var serverPort: Int`; in the default
    // `MOCK` environment that placeholder is never populated and bean
    // injection fails with "Could not resolve placeholder 'local.server.port'".
    // We don't actually hit any HTTP endpoint from this test, but the field
    // injection runs unconditionally — so spin up a random servlet port to
    // satisfy the placeholder.
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("ImapMirrorService — lossless APPEND with Message-ID dedup (issue #23)")
class ImapMirrorServiceTest : BaseIT() {

    companion object {
        private const val SRC_PORT = 3143
        private const val DST_PORT = 3145

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

    @Autowired lateinit var mirrorService: ImapMirrorService
    @Autowired lateinit var mirrorConfigRepository: MirrorConfigRepository
    @Autowired lateinit var mirroredMessageRepository: MirroredMessageRepository
    @Autowired lateinit var emailAccountRepository: EmailAccountRepository
    @Autowired lateinit var emailAccountService: EmailAccountService
    @Autowired lateinit var rateLimiter: MirrorRateLimiter

    private var configId: Long = 0

    @BeforeEach
    fun setUp() {
        // Per-test clean slate on both IMAP servers; GreenMail's reset wipes
        // all users and stored mail so previous tests can't leak in.
        srcServer.reset()
        dstServer.reset()
        srcServer.setUser(SRC_EMAIL, SRC_EMAIL, PASSWORD)
        dstServer.setUser(DST_EMAIL, DST_EMAIL, PASSWORD)

        mirroredMessageRepository.deleteAll()
        mirrorConfigRepository.deleteAll()
        emailAccountRepository.deleteAll()
        rateLimiter.reset()

        val srcAccount = persistAccount(SRC_EMAIL, SRC_PORT)
        val dstAccount = persistAccount(DST_EMAIL, DST_PORT)

        configId = mirrorConfigRepository.save(
            MirrorConfig().apply {
                uri = "mirror://test-${System.nanoTime()}"
                name = "test mirror"
                sourceAccountId = srcAccount
                destAccountId = dstAccount
                enabled = true
                appendRateLimitPerSecond = null // unthrottled in tests
            }
        ).id!!
    }

    @AfterEach
    fun tearDown() {
        mirroredMessageRepository.deleteAll()
        mirrorConfigRepository.deleteAll()
        emailAccountRepository.deleteAll()
    }

    @Test
    @DisplayName("mirrorMessage copies body, flags, and INTERNALDATE; records MirroredMessage")
    fun copiesBodyFlagsAndInternalDate() {
        val internalDate = Date.from(Instant.parse("2024-03-15T10:00:00Z"))
        val messageId = "<lossless-copy@oconeco.com>"
        val sourceUid = deliverMessage(
            subject = "From WorkMail",
            body = "test body for lossless copy",
            messageId = messageId,
            internalDate = internalDate,
            flagsToSet = arrayOf(Flags.Flag.SEEN, Flags.Flag.FLAGGED)
        )

        val result = mirrorService.mirrorMessage(configId, "INBOX", sourceUid, "INBOX")

        assertThat(result).isInstanceOf(MirrorResult.Copied::class.java)

        // The destination message body must match — proving APPEND wrote
        // the full RFC822 octets, not just headers.
        val destMessages = fetchAllSnapshots(DST_PORT, DST_EMAIL, "INBOX")
        assertThat(destMessages).hasSize(1)
        val destMsg = destMessages.single()
        assertThat(destMsg.body).contains("test body for lossless copy")
        assertThat(destMsg.messageId).isEqualTo(messageId)

        // Flag preservation — both \Seen and \Flagged round-tripped via APPEND.
        assertThat(destMsg.flags.contains(Flags.Flag.SEEN)).isTrue
        assertThat(destMsg.flags.contains(Flags.Flag.FLAGGED)).isTrue

        // INTERNALDATE preservation — note GreenMail honours the supplied
        // INTERNALDATE on APPEND; Gmail/Cloudflare/Fastmail/Outlook do too
        // per ADR-005 (some servers stamp their own; documented limitation).
        assertThat(destMsg.internalDate).isEqualTo(internalDate)

        // Audit row written with the right keys.
        val record = mirroredMessageRepository.findByMirrorConfigIdAndMessageId(configId, messageId)
        assertThat(record).isNotNull
        assertThat(record!!.sourceFolder).isEqualTo("INBOX")
        assertThat(record.destFolder).isEqualTo("INBOX")
        assertThat(record.sourceUid).isEqualTo(sourceUid)
    }

    @Test
    @DisplayName("re-mirroring same message returns AlreadyMirrored without touching dest")
    fun reMirroringSameMessageIsIdempotent() {
        val messageId = "<idempotent@oconeco.com>"
        val sourceUid = deliverMessage(
            subject = "idempotent test",
            body = "should only appear once on dest",
            messageId = messageId,
            internalDate = Date(),
            flagsToSet = arrayOf(Flags.Flag.SEEN)
        )

        val first = mirrorService.mirrorMessage(configId, "INBOX", sourceUid, "INBOX")
        val second = mirrorService.mirrorMessage(configId, "INBOX", sourceUid, "INBOX")

        assertThat(first).isInstanceOf(MirrorResult.Copied::class.java)
        assertThat(second).isInstanceOf(MirrorResult.AlreadyMirrored::class.java)

        // The destination must hold exactly one copy — proving the second
        // call short-circuited before APPEND.
        val destMessages = fetchAllSnapshots(DST_PORT, DST_EMAIL, "INBOX")
        assertThat(destMessages).hasSize(1)

        // And only one audit row exists.
        assertThat(mirroredMessageRepository.countByMirrorConfigId(configId)).isEqualTo(1)
    }

    @Test
    @DisplayName("message without Message-ID gets a synthetic id and dedups correctly")
    fun syntheticIdForMessagesWithoutMessageId() {
        // GreenMail's setUser stores raw RFC822, so we hand-craft a MIME
        // message *without* a Message-ID header by clearing it before delivery.
        val raw = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress("sender@example.com"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, "$SRC_EMAIL")
            subject = "no message-id header"
            setText("orphan body")
            // Compose, then strip Message-ID so jakarta.mail doesn't auto-add.
            saveChanges()
            removeHeader("Message-ID")
        }
        srcServer.managers.imapHostManager.getInbox(srcServer.managers.userManager.getUser(SRC_EMAIL))
            .appendMessage(raw, Flags(), Date())
        val sourceUid = lastSourceUid("INBOX")

        val first = mirrorService.mirrorMessage(configId, "INBOX", sourceUid, "INBOX")
        val second = mirrorService.mirrorMessage(configId, "INBOX", sourceUid, "INBOX")

        assertThat(first).isInstanceOf(MirrorResult.Copied::class.java)
        assertThat(second).isInstanceOf(MirrorResult.AlreadyMirrored::class.java)

        val syntheticKey = "synthetic:$configId:INBOX:$sourceUid"
        val record = mirroredMessageRepository.findByMirrorConfigIdAndMessageId(configId, syntheticKey)
        assertThat(record).isNotNull
        assertThat(record!!.messageId).isEqualTo(syntheticKey)
    }

    @Test
    @DisplayName("APPEND failure with destination server down returns Failed(retryable=true)")
    fun appendFailureIsRetryable() {
        val sourceUid = deliverMessage(
            subject = "will fail",
            body = "destination is down",
            messageId = "<dest-down@oconeco.com>",
            internalDate = Date(),
            flagsToSet = emptyArray()
        )

        // Bring the destination IMAP listener down so APPEND can't connect.
        // The connect failure surfaces as a MessagingException whose cause
        // is an IOException — the service maps that to retryable=true.
        dstServer.stop()
        try {
            val result = mirrorService.mirrorMessage(configId, "INBOX", sourceUid, "INBOX")
            assertThat(result).isInstanceOf(MirrorResult.Failed::class.java)
            val failed = result as MirrorResult.Failed
            assertThat(failed.retryable).isTrue
        } finally {
            // Restart so other tests in this class (or re-runs) start clean.
            dstServer = GreenMail(ServerSetup(DST_PORT, null, "imap")).also { it.start() }
            dstServer.setUser(DST_EMAIL, DST_EMAIL, PASSWORD)
        }

        // No audit row was written — the APPEND never completed.
        assertThat(mirroredMessageRepository.countByMirrorConfigId(configId)).isEqualTo(0)
    }

    @Test
    @DisplayName("rate limiter throttles consecutive APPENDs to roughly the configured rate")
    fun rateLimiterThrottlesAppends() {
        // 2 APPENDs/sec → second call must wait at least ~250ms after the first.
        // We can't reuse the no-throttle config from setUp; create a throttled one.
        val throttledConfigId = mirrorConfigRepository.save(
            MirrorConfig().apply {
                uri = "mirror://throttled-${System.nanoTime()}"
                name = "throttled"
                sourceAccountId = mirrorConfigRepository.findById(configId).get().sourceAccountId
                destAccountId = mirrorConfigRepository.findById(configId).get().destAccountId
                enabled = true
                appendRateLimitPerSecond = 2
            }
        ).id!!

        val uid1 = deliverMessage("rate-1", "body1", "<rate-1@oconeco.com>", Date(), emptyArray())
        val uid2 = deliverMessage("rate-2", "body2", "<rate-2@oconeco.com>", Date(), emptyArray())

        // First call primes the bucket; second one consumes the remaining
        // token and the third must wait for refill (~500ms at 2/sec).
        mirrorService.mirrorMessage(throttledConfigId, "INBOX", uid1, "INBOX")
        mirrorService.mirrorMessage(throttledConfigId, "INBOX", uid2, "INBOX")

        val uid3 = deliverMessage("rate-3", "body3", "<rate-3@oconeco.com>", Date(), emptyArray())
        val start = System.nanoTime()
        mirrorService.mirrorMessage(throttledConfigId, "INBOX", uid3, "INBOX")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // The third APPEND should have been blocked on the bucket for some
        // non-trivial fraction of a second. 200ms is a safe lower bound that
        // tolerates CI variance while still proving the throttle fired.
        assertThat(elapsedMs).isGreaterThan(200L)
    }

    // ---- helpers ---------------------------------------------------------

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

    private fun deliverMessage(
        subject: String,
        body: String,
        messageId: String,
        internalDate: Date,
        flagsToSet: Array<Flags.Flag>
    ): Long {
        val msg = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress("sender@example.com"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, SRC_EMAIL)
            this.subject = subject
            setText(body)
            saveChanges()
            setHeader("Message-ID", messageId)
        }
        val flags = Flags().apply { flagsToSet.forEach { add(it) } }
        srcServer.managers.imapHostManager
            .getInbox(srcServer.managers.userManager.getUser(SRC_EMAIL))
            .appendMessage(msg, flags, internalDate)
        return lastSourceUid("INBOX")
    }

    private fun lastSourceUid(folderName: String): Long {
        // Read the freshly-appended UID via a brief IMAP session — avoids
        // coupling to GreenMail's internal numbering.
        val store = Session.getInstance(Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", SRC_PORT.toString())
        }).getStore("imap")
        store.connect("127.0.0.1", SRC_PORT, SRC_EMAIL, PASSWORD)
        try {
            val folder = store.getFolder(folderName) as com.sun.mail.imap.IMAPFolder
            folder.open(Folder.READ_ONLY)
            try {
                val all = folder.messages
                val last = all.last()
                return folder.getUID(last)
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }

    /**
     * Snapshot of an IMAP message that detaches from the IMAP folder
     * (which gets closed before the test assertions run, so reading any
     * lazy field after that point would trip `FolderClosedException`).
     */
    private data class MessageSnapshot(
        val body: String,
        val messageId: String?,
        val flags: Flags,
        val internalDate: Date?
    )

    private fun fetchAllSnapshots(port: Int, email: String, folderName: String): List<MessageSnapshot> {
        val store = Session.getInstance(Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", port.toString())
        }).getStore("imap")
        store.connect("127.0.0.1", port, email, PASSWORD)
        try {
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            try {
                return folder.messages.map { raw ->
                    val msg = raw as MimeMessage
                    MessageSnapshot(
                        body = msg.content.toString(),
                        messageId = msg.getHeader("Message-ID")?.firstOrNull(),
                        flags = Flags(msg.flags),
                        internalDate = msg.receivedDate
                    )
                }
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }
}
