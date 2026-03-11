package com.oconeco.spring_search_tempo.base.service

import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeMessage
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.charset.Charset


/**
 * Service for extracting text content from email messages.
 *
 * Handles multipart messages, HTML content, and plain text.
 * Sanitizes output for PostgreSQL (removes null bytes).
 */
@Service
class EmailTextExtractionService {

    companion object {
        private val log = LoggerFactory.getLogger(EmailTextExtractionService::class.java)
        private const val MAX_TEXT_LENGTH = 1_000_000  // 1MB limit for body text
    }

    /**
     * Extract text content from an email message.
     *
     * @param message The email message to extract from
     * @return Result containing extracted text or error
     */
    fun extractText(message: Message): EmailTextResult {
        return try {
            val content = getPartContentSafely(message)
            val text = when {
                content is String -> content
                content is Multipart -> extractFromMultipart(content)
                message.isMimeType("text/plain") -> extractTextFromRawPart(message) ?: ""
                message.isMimeType("text/html") -> {
                    val html = extractTextFromRawPart(message)
                    if (html.isNullOrBlank()) "" else extractTextFromHtml(html)
                }
                else -> "[Unsupported content type: ${message.contentType}]"
            }

            // Sanitize for PostgreSQL and limit length
            val sanitized = sanitizeText(text)
            EmailTextResult.Success(sanitized)

        } catch (e: Exception) {
            log.error("Failed to extract email text: {}", e.message, e)
            EmailTextResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Extract text from a multipart message.
     * Prefers plain text over HTML, falls back to HTML conversion.
     */
    private fun extractFromMultipart(multipart: Multipart): String {
        val textParts = mutableListOf<String>()
        var htmlContent: String? = null

        for (i in 0 until multipart.count) {
            val part = multipart.getBodyPart(i)
            val partContent = getPartContentSafely(part)

            when {
                part.isMimeType("text/plain") && !isAttachment(part) -> {
                    val text = (partContent as? String) ?: extractTextFromRawPart(part)
                    if (!text.isNullOrBlank()) {
                        textParts.add(text)
                    }
                }
                part.isMimeType("text/html") && !isAttachment(part) -> {
                    htmlContent = (partContent as? String) ?: extractTextFromRawPart(part)
                }
                partContent is Multipart -> {
                    // Recursively extract from nested multipart
                    val nested = extractFromMultipart(partContent)
                    if (nested.isNotBlank()) {
                        textParts.add(nested)
                    }
                }
            }
        }

        // Prefer plain text, fall back to HTML extraction
        return if (textParts.isNotEmpty()) {
            textParts.joinToString("\n\n")
        } else if (htmlContent != null) {
            extractTextFromHtml(htmlContent)
        } else {
            ""
        }
    }

    /**
     * Check if a part is an attachment (not inline content).
     */
    private fun isAttachment(part: Part): Boolean {
        return Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)
    }

    /**
     * Extract plain text from HTML content using Jsoup.
     */
    private fun extractTextFromHtml(html: String): String {
        return try {
            Jsoup.parse(html).text()
        } catch (e: Exception) {
            log.warn("Failed to parse HTML: {}", e.message)
            // Fall back to simple tag stripping
            html.replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    /**
     * Sanitize text for PostgreSQL storage.
     * Removes null bytes and limits length.
     */
    private fun sanitizeText(text: String): String {
        var sanitized = text
            .replace("\u0000", "")  // Remove null bytes (PostgreSQL doesn't like these)
            .replace("\\x00", "")   // Alternative null byte representation

        // Limit length
        if (sanitized.length > MAX_TEXT_LENGTH) {
            log.info("Truncating email body from {} to {} characters", sanitized.length, MAX_TEXT_LENGTH)
            sanitized = sanitized.take(MAX_TEXT_LENGTH)
        }

        return sanitized
    }

    /**
     * Extract attachment information from a message.
     *
     * @return List of attachment filenames
     */
    fun extractAttachmentInfo(message: Message): List<String> {
        val attachments = mutableListOf<String>()

        try {
            val content = getPartContentSafely(message)
            if (content is Multipart) {
                extractAttachmentsFromMultipart(content, attachments)
            }
        } catch (e: Exception) {
            log.warn("Failed to extract attachment info: {}", e.message)
        }

        return attachments
    }

    private fun extractAttachmentsFromMultipart(multipart: Multipart, attachments: MutableList<String>) {
        for (i in 0 until multipart.count) {
            val part = multipart.getBodyPart(i)
            val partContent = getPartContentSafely(part)

            val fileName = try {
                part.fileName
            } catch (e: Exception) {
                log.debug("Failed to decode attachment file name: {}", e.message)
                null
            }

            if (isAttachment(part) || fileName != null) {
                fileName?.let { attachments.add(it) }
            }

            if (partContent is Multipart) {
                extractAttachmentsFromMultipart(partContent, attachments)
            }
        }
    }

    private fun getPartContentSafely(part: Part): Any? {
        return try {
            part.content
        } catch (e: Exception) {
            if (isUnknownTransferEncodingError(e)) {
                val encoding = runCatching { (part as? MimePart)?.encoding }.getOrNull() ?: "unknown"
                log.warn("Skipping MIME decode for unknown transfer encoding '{}' in part {}", encoding, part.contentType)
                null
            } else {
                throw e
            }
        }
    }

    private fun extractTextFromRawPart(part: Part): String? {
        val rawInputStream = when (part) {
            is MimeBodyPart -> runCatching { part.rawInputStream }.getOrNull()
            is MimeMessage -> runCatching { part.rawInputStream }.getOrNull()
            else -> null
        } ?: return null

        return try {
            rawInputStream.use { raw ->
                val bytes = raw.readBytes()
                if (bytes.isEmpty()) {
                    null
                } else {
                    val declaredCharset = resolveCharset(part.contentType)
                    decodeToString(bytes, declaredCharset)
                        .trim()
                        .takeIf { it.isNotBlank() }
                }

            }
        } catch (e: Exception) {
            log.debug("Failed raw text fallback for part {}: {}", part.contentType, e.message)
            null
        }
    }

    private fun resolveCharset(contentType: String?): Charset {
        val candidate = runCatching { ContentType(contentType).getParameter("charset") }
            .getOrNull()
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }

        if (candidate != null) {
            try {
                if (Charset.isSupported(candidate)) {
                    return Charset.forName(candidate)
                }
            } catch (_: Exception) {
                // Ignore malformed charset names and use fallback.
            }
        }
        return Charsets.UTF_8
    }

    private fun decodeToString(bytes: ByteArray, preferredCharset: Charset): String {
        return runCatching { String(bytes, preferredCharset) }
            .recoverCatching { String(bytes, Charsets.UTF_8) }
            .recoverCatching { String(bytes, Charsets.ISO_8859_1) }
            .getOrDefault(String(bytes))
    }

    private fun isUnknownTransferEncodingError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message
            if (message != null && message.contains("Unknown encoding", ignoreCase = true)) {
                return true
            }
            current = current.cause
        }
        return false
    }
}

/**
 * Result of email text extraction.
 */
sealed class EmailTextResult {
    data class Success(val text: String) : EmailTextResult()
    data class Failure(val error: String) : EmailTextResult()
}
