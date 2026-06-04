package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.service.EmailAttachmentExtractionService
import com.oconeco.spring_search_tempo.base.service.EmailTextExtractionService
import com.oconeco.spring_search_tempo.base.service.EmailTextResult
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import jakarta.mail.Session
import java.util.Properties

/**
 * Issue #58: the prefetch processor must extract from the pre-cached
 * [Message] handle handed to it by [PrefetchingBodyEnrichmentReader] without
 * doing any additional IMAP work. This test pins:
 *
 *   1. Successful body + attachment extraction round-trips through the
 *      result DTO.
 *   2. A text-extraction failure surfaces as a `[Extraction failed: …]`
 *      placeholder body — same contract as the legacy processor — so
 *      downstream callers don't see a hard null.
 */
@DisplayName("PrefetchedBodyEnrichmentProcessor — pure CPU extraction (issue #58)")
class PrefetchedBodyEnrichmentProcessorTest {

    private val textService: EmailTextExtractionService = mock(EmailTextExtractionService::class.java)
    private val attachmentService: EmailAttachmentExtractionService = mock(EmailAttachmentExtractionService::class.java)
    private val objectMapper = ObjectMapper()

    private val processor = PrefetchedBodyEnrichmentProcessor(
        emailTextExtractionService = textService,
        emailAttachmentExtractionService = attachmentService,
        objectMapper = objectMapper
    )

    @Test
    @DisplayName("happy path: success result carries body, size, and zero attachments for a plain text message")
    fun happyPath() {
        val dto = EmailMessageDTO().apply { id = 99L }
        val msg = plainTextMessage("hello world")
        `when`(textService.extractText(any(Message::class.java) ?: msg))
            .thenReturn(EmailTextResult.Success("hello world"))

        val result = processor.process(PrefetchedBodyMessage(dto = dto, message = msg))!!

        assertThat(result.messageId).isEqualTo(99L)
        assertThat(result.bodyText).isEqualTo("hello world")
        assertThat(result.bodySize).isEqualTo("hello world".length.toLong())
        assertThat(result.hasAttachments).isFalse
        assertThat(result.attachmentCount).isZero
        // Plain-text messages don't yield Multipart content; attachment
        // service must not be consulted.
        verify(attachmentService, never()).extractAttachments(any(Message::class.java) ?: msg)
    }

    @Test
    @DisplayName("extraction failure: result carries a [Extraction failed: ...] placeholder body")
    fun extractionFailurePreservesContract() {
        val dto = EmailMessageDTO().apply { id = 100L }
        val msg = plainTextMessage("ignored")
        `when`(textService.extractText(any(Message::class.java) ?: msg))
            .thenReturn(EmailTextResult.Failure("ouch"))

        val result = processor.process(PrefetchedBodyMessage(dto = dto, message = msg))!!

        assertThat(result.bodyText).isEqualTo("[Extraction failed: ouch]")
        assertThat(result.bodySize).isZero
    }

    @Test
    @DisplayName("null dto.id: processor returns null (defensive — should never happen, but the legacy processor did the same)")
    fun nullIdYieldsNullResult() {
        val dto = EmailMessageDTO().apply { id = null }
        val msg = plainTextMessage("hello")
        assertThat(processor.process(PrefetchedBodyMessage(dto = dto, message = msg))).isNull()
    }

    // ----- helpers ------------------------------------------------------

    private fun plainTextMessage(body: String): MimeMessage {
        val session = Session.getInstance(Properties())
        return MimeMessage(session).apply {
            setFrom(InternetAddress("from@example.com"))
            addRecipient(Message.RecipientType.TO, InternetAddress("to@example.com"))
            subject = "subject"
            setText(body)
        }
    }
}

