package com.oconeco.spring_search_tempo.base.service

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Properties

@DisplayName("EmailTextExtractionService Tests")
class EmailTextExtractionServiceTest {

    private lateinit var service: EmailTextExtractionService

    @BeforeEach
    fun setup() {
        service = EmailTextExtractionService()
    }

    @Test
    @DisplayName("Should extract body from single-part message with unknown transfer encoding")
    fun extractTextFromSinglePartWithUnknownEncoding() {
        val message = parseMessage(
            """
            From: sender@example.com
            To: receiver@example.com
            Subject: Unknown Encoding Single Part
            MIME-Version: 1.0
            Content-Type: text/plain; charset=UTF-8
            Content-Transfer-Encoding: hexa

            Hello from malformed transfer encoding.
            """.trimIndent()
        )

        val result = service.extractText(message)

        assertTrue(result is EmailTextResult.Success)
        val success = result as EmailTextResult.Success
        assertTrue(success.text.contains("Hello from malformed transfer encoding."))
    }

    @Test
    @DisplayName("Should continue multipart extraction when one part has unknown transfer encoding")
    fun extractTextFromMultipartWithUnknownEncodingPart() {
        val message = parseMessage(
            """
            From: sender@example.com
            To: receiver@example.com
            Subject: Unknown Encoding Multipart
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary="b1"

            --b1
            Content-Type: text/plain; charset=UTF-8
            Content-Transfer-Encoding: hexa

            Malformed plain text part.
            --b1
            Content-Type: text/html; charset=UTF-8

            <html><body><p>Safe HTML part.</p></body></html>
            --b1--
            """.trimIndent()
        )

        val result = service.extractText(message)

        assertTrue(result is EmailTextResult.Success)
        val success = result as EmailTextResult.Success
        assertTrue(
            success.text.contains("Malformed plain text part.") ||
                success.text.contains("Safe HTML part.")
        )
    }

    @Test
    @DisplayName("Should still extract attachment names when a sibling part has unknown transfer encoding")
    fun extractAttachmentInfoWithUnknownEncodingSiblingPart() {
        val message = parseMessage(
            """
            From: sender@example.com
            To: receiver@example.com
            Subject: Attachment With Unknown Encoding Sibling
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="b2"

            --b2
            Content-Type: text/plain; charset=UTF-8
            Content-Transfer-Encoding: hexa

            Malformed body part.
            --b2
            Content-Type: text/plain; name="report.txt"
            Content-Disposition: attachment; filename="report.txt"
            Content-Transfer-Encoding: base64

            SGVsbG8gYXR0YWNobWVudA==
            --b2--
            """.trimIndent()
        )

        val attachments = service.extractAttachmentInfo(message)

        assertEquals(listOf("report.txt"), attachments)
    }

    private fun parseMessage(rawMessage: String): MimeMessage {
        val session = Session.getInstance(Properties())
        val bytes = rawMessage.replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_8)
        return MimeMessage(session, ByteArrayInputStream(bytes))
    }
}
