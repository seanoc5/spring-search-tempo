package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.EmailFolderDTO
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.sun.mail.imap.IMAPMessage
import jakarta.mail.Session
import jakarta.mail.Store
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepExecution
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.util.Properties

/**
 * Issue #58: verify that [PrefetchingBodyEnrichmentReader] uses
 * `FetchProfile` to bulk-prefetch ~`prefetchBatchSize` message bodies per
 * IMAP round-trip — collapsing the per-message FETCH that was capping
 * Gmail throughput at ~25 messages/minute.
 *
 * Strategy:
 *  - GreenMail provides a real IMAP listener (no mocked Folder/Message).
 *  - `EmailAccountService` / `EmailFolderService` / `EmailMessageService`
 *    are stubbed via Mockito — those are DB-bound concerns we don't need
 *    here. `ImapConnectionService` is a tiny test double that connects
 *    straight to GreenMail.
 *  - The assertion that matters: round-trip count == ceil(N / batchSize).
 *    That is the architectural change. (The old serial reader did N
 *    round-trips; the new prefetch reader does N/batchSize.)
 *  - We also confirm each emitted message can be `.getContent()`'d
 *    without an extra fetch — i.e. the prefetch actually populated the
 *    message body, not just the envelope.
 */
@DisplayName("PrefetchingBodyEnrichmentReader — bulk FetchProfile prefetch (issue #58)")
class PrefetchingBodyEnrichmentReaderTest {

    companion object {
        // Dynamic port (issue #106): hardcoded 3143 collided across
        // GreenMail-backed tests sharing the same JVM after Spring
        // tear-down.
        @JvmField
        @RegisterExtension
        val greenMail = GreenMailExtension(ServerSetupTest.IMAP.dynamicPort())

        private const val USER_EMAIL = "bulkfetch@example.com"
        private const val USER_PASSWORD = "secret"
        private const val ACCOUNT_ID = 42L
        private const val FOLDER_ID = 7L
    }

    private lateinit var accountService: EmailAccountService
    private lateinit var folderService: EmailFolderService
    private lateinit var messageService: EmailMessageService
    private lateinit var imapConnectionService: ImapConnectionService

    @BeforeEach
    fun setUp() {
        greenMail.setUser(USER_EMAIL, USER_EMAIL, USER_PASSWORD)
        accountService = mock(EmailAccountService::class.java)
        folderService = mock(EmailFolderService::class.java)
        messageService = mock(EmailMessageService::class.java)
        // Wire a fake ImapConnectionService that connects straight to GreenMail —
        // production wiring needs encryption-key resolution we don't want to
        // simulate here.
        imapConnectionService = fakeImapConnectionService()

        val accountDto = EmailAccountDTO().apply {
            id = ACCOUNT_ID
            email = USER_EMAIL
            provider = EmailProvider.GENERIC_IMAP
            imapHost = "127.0.0.1"
            imapPort = greenMail.imap.port
            useSsl = false
        }
        val folderDto = EmailFolderDTO().apply {
            id = FOLDER_ID
            folderName = "INBOX"
        }
        `when`(accountService.get(ACCOUNT_ID)).thenReturn(accountDto)
        `when`(folderService.get(FOLDER_ID)).thenReturn(folderDto)
    }

    @Test
    @DisplayName("100 messages with batch size 25 produce ~4 IMAP round-trips, not 100")
    fun prefetchCollapsesRoundTrips() {
        val n = 100
        val batchSize = 25
        deliverMessages(n)

        val dtos = (0 until n).map { i ->
            EmailMessageDTO().apply {
                id = 1000L + i
                imapUid = uidByIndex(i)
                emailAccount = ACCOUNT_ID
                emailFolder = FOLDER_ID
            }
        }

        `when`(messageService.countHeadersOnlyByAccount(ACCOUNT_ID)).thenReturn(n.toLong())
        `when`(messageService.findHeadersOnlyByAccount(anyLong(), anyPageable()))
            .thenReturn(pageOf(dtos))

        val reader = PrefetchingBodyEnrichmentReader(
            imapConnectionService = imapConnectionService,
            emailAccountService = accountService,
            emailFolderService = folderService,
            emailMessageService = messageService,
            accountId = ACCOUNT_ID,
            pageSize = n,
            prefetchBatchSize = batchSize
        )

        val emitted = readAll(reader)

        // Architectural assertion: prefetch path used FetchProfile so the
        // number of FETCH round-trips is ceil(N / batchSize), not N.
        assertThat(reader.getPrefetchRoundTrips()).isEqualTo(n / batchSize)
        assertThat(emitted).hasSize(n)

        // Bodies are accessible without another IMAP round-trip. We can't
        // easily count round-trips at the JavaMail layer, but if the prefetch
        // didn't materialize the body, `.getContent()` would either throw or
        // hit the network — neither of which would survive the reader's
        // afterStep closing the folder. So calling getContent() *after*
        // afterStep is the test: it must succeed because the body was
        // cached client-side by FetchProfile.MESSAGE.
        reader.afterStep(stubStepExecution())

        emitted.forEach { wrapper ->
            val content = (wrapper.message as IMAPMessage).content
            assertThat(content).isNotNull
            // Bodies are small text payloads, so the cached content is the
            // payload string GreenMail delivered.
            assertThat(content.toString()).contains("body-")
        }
    }

    @Test
    @DisplayName("empty backlog: no IMAP round-trips and no folder open")
    fun emptyBacklogIsANoop() {
        `when`(messageService.countHeadersOnlyByAccount(ACCOUNT_ID)).thenReturn(0L)

        val reader = PrefetchingBodyEnrichmentReader(
            imapConnectionService = imapConnectionService,
            emailAccountService = accountService,
            emailFolderService = folderService,
            emailMessageService = messageService,
            accountId = ACCOUNT_ID,
            pageSize = 100,
            prefetchBatchSize = 50
        )

        assertThat(reader.read()).isNull()
        assertThat(reader.getPrefetchRoundTrips()).isZero
        reader.afterStep(stubStepExecution())
    }

    @Test
    @DisplayName("ragged tail: 30 messages with batch size 25 emits 2 round-trips")
    fun raggedTailStillCorrect() {
        val n = 30
        val batchSize = 25
        deliverMessages(n)

        val dtos = (0 until n).map { i ->
            EmailMessageDTO().apply {
                id = 2000L + i
                imapUid = uidByIndex(i)
                emailAccount = ACCOUNT_ID
                emailFolder = FOLDER_ID
            }
        }

        `when`(messageService.countHeadersOnlyByAccount(ACCOUNT_ID)).thenReturn(n.toLong())
        `when`(messageService.findHeadersOnlyByAccount(anyLong(), anyPageable()))
            .thenReturn(pageOf(dtos))

        val reader = PrefetchingBodyEnrichmentReader(
            imapConnectionService = imapConnectionService,
            emailAccountService = accountService,
            emailFolderService = folderService,
            emailMessageService = messageService,
            accountId = ACCOUNT_ID,
            pageSize = n,
            prefetchBatchSize = batchSize
        )

        val emitted = readAll(reader)
        assertThat(emitted).hasSize(n)
        // ceil(30 / 25) == 2
        assertThat(reader.getPrefetchRoundTrips()).isEqualTo(2)
        reader.afterStep(stubStepExecution())
    }

    // ----- helpers --------------------------------------------------------

    private fun readAll(reader: PrefetchingBodyEnrichmentReader): List<PrefetchedBodyMessage> {
        val out = mutableListOf<PrefetchedBodyMessage>()
        var item = reader.read()
        while (item != null) {
            out.add(item)
            item = reader.read()
        }
        return out
    }

    /**
     * Deliver [count] distinct text messages directly to the user's inbox
     * via GreenMail's API (skips SMTP — we only registered the IMAP listener).
     * UIDs follow the IMAP convention of starting at 1; see [uidByIndex].
     */
    private fun deliverMessages(count: Int) {
        val user = greenMail.userManager.getUser(USER_EMAIL)
        val session = Session.getInstance(Properties())
        repeat(count) { i ->
            val msg = jakarta.mail.internet.MimeMessage(session).apply {
                setFrom(jakarta.mail.internet.InternetAddress("sender@example.com"))
                addRecipient(
                    jakarta.mail.Message.RecipientType.TO,
                    jakarta.mail.internet.InternetAddress(USER_EMAIL)
                )
                subject = "Subject $i"
                setText("body-$i")
            }
            user.deliver(msg)
        }
    }

    private fun uidByIndex(i: Int): Long = (i + 1).toLong()

    private fun pageOf(dtos: List<EmailMessageDTO>): Page<EmailMessageDTO> = PageImpl(dtos)

    private fun stubStepExecution(): StepExecution {
        val stepExec = mock(StepExecution::class.java)
        `when`(stepExec.exitStatus).thenReturn(ExitStatus.COMPLETED)
        return stepExec
    }

    private fun fakeImapConnectionService(): ImapConnectionService {
        // We can't easily subclass ImapConnectionService because its ctor
        // takes services we don't want to wire. Use Mockito to intercept
        // connect() and return a real GreenMail-backed store. The
        // `doAnswer().when()` form (vs `when().thenAnswer()`) avoids the
        // Kotlin null check on the matcher placeholder.
        val svc = mock(ImapConnectionService::class.java)
        org.mockito.Mockito.doAnswer { connectToGreenMail() }
            .`when`(svc).connect(anyDto())
        return svc
    }

    /**
     * Kotlin-safe wildcard matcher for [EmailAccountDTO]. `ArgumentMatchers.any()`
     * returns null; assigning null to a non-null Kotlin parameter trips the
     * compiler-inserted null check at the call site. We register the matcher
     * on Mockito's thread-local stack and return a non-null placeholder so
     * Kotlin is happy.
     */
    private fun anyDto(): EmailAccountDTO {
        org.mockito.ArgumentMatchers.any(EmailAccountDTO::class.java)
        return EmailAccountDTO()
    }

    /** Kotlin-safe wildcard matcher for [Pageable]. */
    private fun anyPageable(): Pageable {
        org.mockito.ArgumentMatchers.any(Pageable::class.java)
        return Pageable.unpaged()
    }

    private fun connectToGreenMail(): Store {
        val props = Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", greenMail.imap.port.toString())
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imap")
        store.connect("127.0.0.1", greenMail.imap.port, USER_EMAIL, USER_PASSWORD)
        return store
    }

}
