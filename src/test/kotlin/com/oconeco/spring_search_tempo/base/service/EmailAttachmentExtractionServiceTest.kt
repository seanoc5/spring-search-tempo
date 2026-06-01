package com.oconeco.spring_search_tempo.base.service

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Properties

@DisplayName("EmailAttachmentExtractionService Tests")
class EmailAttachmentExtractionServiceTest {

    private val textExtractionService = TextExtractionService()

    private fun service(maxSize: Long = 25L * 1024 * 1024, enabled: Boolean = true) =
        EmailAttachmentExtractionService(
            textExtractionService = textExtractionService,
            maxAttachmentSize = maxSize,
            extractionEnabled = enabled
        )

    @Test
    @DisplayName("text/plain attachment is streamed through Tika and indexed")
    fun textAttachmentExtracted() {
        // "Total due: $100" base64 = VG90YWwgZHVlOiAkMTAw
        val message = parseMessage(
            """
            From: sender@example.com
            To: receiver@example.com
            Subject: Invoice
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="b1"

            --b1
            Content-Type: text/plain; charset=UTF-8

            see attached
            --b1
            Content-Type: text/plain; name="invoice.txt"
            Content-Disposition: attachment; filename="invoice.txt"
            Content-Transfer-Encoding: base64

            VG90YWwgZHVlOiAkMTAw
            --b1--
            """.trimIndent()
        )

        val results = service().extractAttachments(message)

        assertEquals(1, results.size, "Should produce exactly one attachment chunk")
        val attachment = results.single()
        assertEquals("invoice.txt", attachment.fileName)
        assertEquals(AttachmentStatus.EXTRACTED, attachment.status)
        assertNotNull(attachment.text)
        assertTrue(attachment.text!!.contains("Total due"),
            "Extracted text should contain attachment body — got: '${attachment.text}'")
    }

    @Test
    @DisplayName("oversized attachment is skipped with placeholder + size metadata")
    fun oversizedAttachmentSkipped() {
        // 1 KB of "A" → base64-encoded body. Cap below at 100 bytes so the
        // attachment trips the size guard before Tika is invoked.
        val payload = "A".repeat(1024)
        val message = multipartMessageWithAttachment(
            attachmentName = "big.txt",
            attachmentContentType = "text/plain",
            payload = payload
        )

        val results = service(maxSize = 100).extractAttachments(message)

        assertEquals(1, results.size)
        val attachment = results.single()
        assertEquals(AttachmentStatus.SKIPPED_TOO_LARGE, attachment.status)
        assertEquals("[Skipped: too large]", attachment.text)
        assertNotNull(attachment.size, "Size should be captured for skipped attachments")
        assertTrue(attachment.size!! > 100, "Captured size should reflect raw attachment bytes")
    }

    @Test
    @DisplayName("binary attachment (image/jpeg) gets LOCATE metadata only — no extraction")
    fun binaryAttachmentLocateOnly() {
        val message = multipartMessageWithAttachment(
            attachmentName = "photo.jpg",
            attachmentContentType = "image/jpeg",
            payload = "fake-jpeg-bytes"
        )

        val results = service().extractAttachments(message)

        assertEquals(1, results.size)
        val attachment = results.single()
        assertEquals("photo.jpg", attachment.fileName)
        assertEquals(AttachmentStatus.LOCATE_ONLY, attachment.status)
        assertNull(attachment.text, "Binary attachments should not carry extracted text")
        assertTrue(attachment.contentType!!.startsWith("image/"))
    }

    @Test
    @DisplayName("extraction-disabled toggle returns no attachment chunks")
    fun extractionDisabledSkipsAll() {
        val message = multipartMessageWithAttachment(
            attachmentName = "invoice.txt",
            attachmentContentType = "text/plain",
            payload = "Total due: \$100"
        )

        val results = service(enabled = false).extractAttachments(message)

        assertTrue(results.isEmpty(),
            "Disabled toggle should return empty list — got ${results.size} results")
    }

    @Test
    @DisplayName("encrypted/corrupt attachment yields extraction-failed placeholder, not a crash")
    fun corruptAttachmentYieldsPlaceholder() {
        // Send raw bytes that announce themselves as a PDF but aren't —
        // Tika will throw TikaException. The service must surface this as
        // a placeholder, never crash.
        val message = multipartMessageWithAttachment(
            attachmentName = "encrypted.pdf",
            attachmentContentType = "application/pdf",
            payload = "%PDF-1.4\n%not-actually-a-pdf"
        )

        val results = service().extractAttachments(message)

        assertEquals(1, results.size)
        val attachment = results.single()
        assertEquals("encrypted.pdf", attachment.fileName)
        assertEquals(AttachmentStatus.EXTRACTION_FAILED, attachment.status)
        assertNotNull(attachment.text)
        assertTrue(attachment.text!!.startsWith("[Extraction failed:"),
            "Failed extraction should produce a bracketed placeholder — got: '${attachment.text}'")
    }

    @Test
    @DisplayName("non-multipart message produces no attachments")
    fun singlePartMessageHasNoAttachments() {
        val message = parseMessage(
            """
            From: sender@example.com
            To: receiver@example.com
            Subject: Plain
            MIME-Version: 1.0
            Content-Type: text/plain; charset=UTF-8

            no attachments here
            """.trimIndent()
        )

        val results = service().extractAttachments(message)

        assertTrue(results.isEmpty())
    }

    @Test
    @DisplayName("inline body parts (no Content-Disposition: attachment) are ignored")
    fun inlineBodyPartsIgnored() {
        // Two body parts: text/plain inline + text/html inline. Neither has
        // Content-Disposition: attachment, so neither becomes a chunk.
        val message = parseMessage(
            """
            From: sender@example.com
            To: receiver@example.com
            Subject: Multipart alternative
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary="b1"

            --b1
            Content-Type: text/plain; charset=UTF-8

            plain version
            --b1
            Content-Type: text/html; charset=UTF-8

            <p>html version</p>
            --b1--
            """.trimIndent()
        )

        val results = service().extractAttachments(message)

        assertTrue(results.isEmpty(),
            "Inline alternatives should not be persisted as attachment chunks — got ${results.size}")
    }

    private fun multipartMessageWithAttachment(
        attachmentName: String,
        attachmentContentType: String,
        payload: String
    ): MimeMessage {
        // Use no-wrap encoder so the base64 stays on a single physical
        // line — multi-line base64 introduced by getMimeEncoder() collides
        // with trimIndent() on the surrounding raw-template MIME.
        val base64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return parseMessage(
            """
            From: sender@example.com
            To: receiver@example.com
            Subject: With Attachment
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="b1"

            --b1
            Content-Type: text/plain; charset=UTF-8

            body
            --b1
            Content-Type: $attachmentContentType; name="$attachmentName"
            Content-Disposition: attachment; filename="$attachmentName"
            Content-Transfer-Encoding: base64

            $base64
            --b1--
            """.trimIndent()
        )
    }

    private fun parseMessage(rawMessage: String): MimeMessage {
        val session = Session.getInstance(Properties())
        val bytes = rawMessage.replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_8)
        return MimeMessage(session, ByteArrayInputStream(bytes))
    }
}
