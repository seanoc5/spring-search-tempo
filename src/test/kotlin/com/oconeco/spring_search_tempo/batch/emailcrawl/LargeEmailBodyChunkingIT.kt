package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.EmailAccount
import com.oconeco.spring_search_tempo.base.domain.EmailMessage
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.domain.FetchStatus
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import com.oconeco.spring_search_tempo.base.service.EmailMessageMapper
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.item.Chunk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Issue #161 / ADR-006 acceptance test (email-side counterpart of #147).
 *
 * Strategy A applied to `email_message`:
 * - `EmailChunkProcessor` sees the full ~2 MB body and produces
 *   ContentChunk rows spanning every byte of the message — including a
 *   unique marker placed at offset 1.8 MB.
 * - `EmailChunkWriter` then truncates `email_message.body_text` to the
 *   configured threshold (50 KB in this test for speed) and appends a
 *   `…[truncated: N chars; full content in chunks]` marker.
 * - The marker term is no longer present in `body_text` (it sits past
 *   byte 1.8 MB, well beyond the 50 KB cap), but it IS present in a
 *   `content_chunks` row, and that row's `fts_vector` matches a
 *   `to_tsquery('english', '<marker>')` query — i.e. FTS at offset 1.8
 *   MB still finds the email via the chunk path.
 * - And the surrounding batch step completes cleanly: no
 *   `string is too long for tsvector` from PostgreSQL on either the
 *   `email_message.fts_vector` (substring-capped at 250K chars in the
 *   column definition) or `content_chunks.fts_vector` (chunked to
 *   sentence-level pieces well under 1 MB each).
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("Large email body chunking (ADR-006 / issue #161)")
class LargeEmailBodyChunkingIT : BaseIT() {

    @Autowired private lateinit var emailAccountRepository: EmailAccountRepository
    @Autowired private lateinit var emailMessageRepository: EmailMessageRepository
    @Autowired private lateinit var emailMessageService: EmailMessageService
    @Autowired private lateinit var emailMessageMapper: EmailMessageMapper
    @Autowired private lateinit var chunkService: ContentChunkService
    @Autowired private lateinit var chunkRepository: ContentChunkRepository

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /** Threshold small enough that a 2 MB body is well past it but the
     *  test runs in seconds. The marker term is at byte ~1.8 MB, ensuring
     *  it lands far past this cap. */
    private val thresholdChars = 50_000L

    /** Pseudo-prose generator — repeating sentence templates so the
     *  sentence chunker has real boundaries to find. A unique marker
     *  string is spliced in at the requested offset. */
    private fun makeProse(totalChars: Int, markerOffset: Int, marker: String): String {
        val template = "The quick brown fox jumps over the lazy dog near the river bank. " +
            "Eager developers ship features while careful operators tune indices. "
        val sb = StringBuilder(totalChars + 64)
        while (sb.length < totalChars) sb.append(template)
        sb.setLength(totalChars)
        // Splice the marker as its own sentence so the chunker keeps it intact.
        val markerSentence = " ${marker.uppercase()} occurs exactly once. "
        val safeOffset = markerOffset.coerceIn(0, sb.length - markerSentence.length)
        sb.replace(safeOffset, safeOffset + markerSentence.length, markerSentence)
        return sb.toString()
    }

    private fun newAccount(): EmailAccount = emailAccountRepository.save(EmailAccount().apply {
        this.email = "test161@oconeco.com"
        this.uri = "email://test161@oconeco.com"
        this.provider = EmailProvider.GENERIC_IMAP
        this.imapHost = "imap.example.com"
        this.imapPort = 993
        this.useSsl = true
        this.enabled = true
        this.version = 1L
    })

    private fun newMessage(account: EmailAccount, body: String): EmailMessage =
        emailMessageRepository.save(EmailMessage().apply {
            this.uri = "email://${account.id}/INBOX/161"
            this.messageId = "<msg-161@oconeco.com>"
            this.imapUid = 161L
            this.fetchStatus = FetchStatus.COMPLETE
            this.subject = "Issue 161 fixture"
            this.fromAddress = "sender@example.com"
            this.toAddresses = """["test161@oconeco.com"]"""
            this.sentDate = OffsetDateTime.of(2026, 6, 24, 12, 0, 0, 0, ZoneOffset.UTC)
            this.receivedDate = OffsetDateTime.of(2026, 6, 24, 12, 0, 0, 0, ZoneOffset.UTC)
            this.bodyText = body
            this.bodySize = body.length.toLong()
            this.emailAccount = account
        })

    @Test
    @Transactional
    fun `2MB email gets chunked fully, body_text truncated, FTS at offset 1_8MB still finds it`() {
        val totalChars = 2 * 1024 * 1024
        val markerOffset = (1.8 * 1024 * 1024).toInt()
        val marker = "zothuriumXYZ161"
        val body = makeProse(totalChars, markerOffset, marker)
        assertThat(body.length).isEqualTo(totalChars)
        assertThat(body.uppercase()).contains(marker.uppercase())

        val account = newAccount()
        val saved = newMessage(account, body)
        val emailId = saved.id!!
        entityManager.flush()

        // Wire up the chunk processor + writer the same way
        // EmailQuickSyncJobBuilder does — minus the reader plumbing,
        // since we're feeding the DTO directly.
        val processor = EmailChunkProcessor()
        val writer = EmailChunkWriter(
            chunkService = chunkService,
            contentChunkRepository = null,
            forceRefresh = false,
            emailMessageService = emailMessageService,
            largeBodyThresholdChars = thresholdChars
        )

        val dto = emailMessageMapper.updateEmailMessageDTO(saved, com.oconeco.spring_search_tempo.base.model.EmailMessageDTO())
        val chunks = processor.process(dto)
        assertThat(chunks).isNotNull
        assertThat(chunks!!).isNotEmpty
        writer.write(Chunk(listOf(chunks)))
        entityManager.flush()
        entityManager.clear()

        // -- Assertion 1: body_text is bounded per policy --
        val reloaded = emailMessageRepository.findById(emailId).orElseThrow()
        val truncated = reloaded.bodyText!!
        assertThat(truncated.length).isLessThanOrEqualTo(thresholdChars.toInt() + 200)
        assertThat(truncated).contains("[truncated:")
        // Marker is past the threshold, so it must NOT survive in body_text.
        assertThat(truncated.uppercase()).doesNotContain(marker.uppercase())

        // -- Assertion 2: chunks cover the (effectively) full message --
        val persistedChunks = chunkRepository.findByEmailMessageIdOrderByChunkNumberAsc(emailId)
        assertThat(persistedChunks).isNotEmpty
        val combinedLen = persistedChunks.sumOf { (it.text ?: "").length }
        assertThat(combinedLen)
            .describedAs("Chunks combined should cover ~all of the original text")
            .isGreaterThan((totalChars * 0.95).toInt())
        val maxEnd = persistedChunks.maxOf { it.endPosition ?: 0L }
        assertThat(maxEnd)
            .describedAs("Last chunk should reach the tail of the message")
            .isGreaterThan((totalChars * 0.95).toLong())

        // -- Assertion 3: FTS for the marker at byte-offset 1.8MB still hits via chunks --
        val markerInChunks = persistedChunks.any {
            (it.text ?: "").uppercase().contains(marker.uppercase())
        }
        assertThat(markerInChunks)
            .describedAs("Marker placed at byte-offset 1.8MB must land in at least one chunk")
            .isTrue()

        // GENERATED fts_vector column matches the marker via a native query.
        val markerTerm = marker.lowercase()
        val hits = entityManager.createNativeQuery(
            """
            SELECT COUNT(*)
            FROM content_chunks c
            WHERE c.email_message_id = :emailId
              AND c.fts_vector @@ to_tsquery('english', :term)
            """.trimIndent()
        )
            .setParameter("emailId", emailId)
            .setParameter("term", markerTerm)
            .singleResult as Number
        assertThat(hits.toInt())
            .describedAs("content_chunks.fts_vector must match the marker for the 1.8MB-offset term")
            .isGreaterThan(0)
    }

    @Test
    @Transactional
    fun `updateBodyAndComplete write-time guard caps a pathologically large body and logs WARN`() {
        // Default threshold is 1 MiB; safety multiplier is 5x → 5 MiB.
        // Build a body just over that to trip the guard.
        val safetyChars = 5 * 1_048_576
        val body = "a".repeat(safetyChars + 1024)
        val account = newAccount()
        val msg = emailMessageRepository.save(EmailMessage().apply {
            this.uri = "email://${account.id}/INBOX/161-guard"
            this.messageId = "<guard-161@oconeco.com>"
            this.imapUid = 162L
            this.fetchStatus = FetchStatus.HEADERS_ONLY
            this.subject = "Issue 161 guard fixture"
            this.fromAddress = "sender@example.com"
            this.toAddresses = """["test161@oconeco.com"]"""
            this.emailAccount = account
        })
        entityManager.flush()

        // updateBodyAndComplete must not raise a tsvector error even
        // though the input dwarfs every reasonable size.
        emailMessageService.updateBodyAndComplete(
            id = msg.id!!,
            bodyText = body,
            bodySize = body.length.toLong(),
            hasAttachments = false,
            attachmentCount = 0,
            attachmentNames = null
        )
        entityManager.flush()
        entityManager.clear()

        val reloaded = emailMessageRepository.findById(msg.id!!).orElseThrow()
        val saved = reloaded.bodyText!!
        // Body was capped at safetyChars + marker.
        assertThat(saved.length).isLessThanOrEqualTo(safetyChars + 200)
        assertThat(saved).contains("[truncated:")
        assertThat(reloaded.fetchStatus).isEqualTo(FetchStatus.COMPLETE)
    }
}
