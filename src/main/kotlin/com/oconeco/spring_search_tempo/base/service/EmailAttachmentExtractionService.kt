package com.oconeco.spring_search_tempo.base.service

import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service


/**
 * Extracts text from attachments on email messages by streaming each
 * `Content-Disposition: attachment` body part through [TextExtractionService].
 *
 * Each attachment becomes one [AttachmentExtraction]; callers persist these as
 * `ContentChunk` rows linked to the parent `EmailMessage`.
 *
 * Decisions encoded here:
 * - **Toggle:** when `app.email.attachment-extraction.enabled=false`, the service
 *   returns an empty list — no attachment chunks are produced.
 * - **Size cap:** attachments above `app.email.max-attachment-size` get a
 *   `[Skipped: too large]` placeholder + size metadata only (no extraction).
 * - **Binary-only types:** images / video / audio take LOCATE-level metadata
 *   only — no Tika call, no text. Other binary formats (PDF, DOCX, ZIP, ...)
 *   go through Tika; encrypted/password-protected variants surface as
 *   `[Extraction failed: encrypted]` instead of crashing the step.
 */
@Service
class EmailAttachmentExtractionService(
    private val textExtractionService: TextExtractionService,
    @Value("\${app.email.max-attachment-size:26214400}") private val maxAttachmentSize: Long,
    @Value("\${app.email.attachment-extraction.enabled:true}") private val extractionEnabled: Boolean
) {

    companion object {
        private val log = LoggerFactory.getLogger(EmailAttachmentExtractionService::class.java)

        // Content-type prefixes that we treat as LOCATE-only (metadata, no text body).
        private val LOCATE_ONLY_PREFIXES = listOf("image/", "video/", "audio/")
    }

    /**
     * Walk the message's MIME tree and extract text from each attachment body part.
     * Returns one [AttachmentExtraction] per attachment encountered.
     *
     * Never throws — per-attachment failures become [AttachmentExtraction]
     * entries with a placeholder text and a failure reason, so one bad
     * attachment can't fail the enclosing batch step.
     */
    fun extractAttachments(message: Message): List<AttachmentExtraction> {
        if (!extractionEnabled) {
            log.debug("Attachment extraction disabled — skipping (max size cap still {} bytes)", maxAttachmentSize)
            return emptyList()
        }

        return try {
            val content = runCatching { message.content }.getOrNull()
            if (content !is Multipart) emptyList()
            else {
                val collected = mutableListOf<AttachmentExtraction>()
                walkMultipart(content, collected)
                collected
            }
        } catch (e: Exception) {
            log.warn("Failed to walk message for attachments: {}", e.message)
            emptyList()
        }
    }

    private fun walkMultipart(multipart: Multipart, collected: MutableList<AttachmentExtraction>) {
        for (i in 0 until multipart.count) {
            val part = try { multipart.getBodyPart(i) } catch (e: Exception) {
                log.debug("Failed to read body part {}: {}", i, e.message)
                continue
            }

            val nestedContent = runCatching { part.content }.getOrNull()
            if (nestedContent is Multipart) {
                walkMultipart(nestedContent, collected)
                continue
            }

            if (isAttachment(part)) {
                collected.add(extractOne(part))
            }
        }
    }

    private fun extractOne(part: Part): AttachmentExtraction {
        val fileName = safeFileName(part) ?: "(unnamed-attachment)"
        val contentType = safeContentType(part)
        val size = safeSize(part)

        // Size cap — placeholder, no stream read.
        if (size != null && size > maxAttachmentSize) {
            log.info("Attachment '{}' exceeds max-attachment-size ({} > {} bytes), skipping extraction",
                fileName, size, maxAttachmentSize)
            return AttachmentExtraction(
                fileName = fileName,
                contentType = contentType,
                size = size,
                text = "[Skipped: too large]",
                status = AttachmentStatus.SKIPPED_TOO_LARGE
            )
        }

        // Binary-only types — LOCATE level, no Tika call.
        if (isLocateOnly(contentType)) {
            log.debug("Attachment '{}' is binary type ({}), storing metadata only", fileName, contentType)
            return AttachmentExtraction(
                fileName = fileName,
                contentType = contentType,
                size = size,
                text = null,
                status = AttachmentStatus.LOCATE_ONLY
            )
        }

        // Stream through Tika.
        return try {
            part.inputStream.use { stream ->
                when (val result = textExtractionService.extractTextFromStream(stream, fileName)) {
                    is TextExtractionResult.Success -> AttachmentExtraction(
                        fileName = fileName,
                        contentType = contentType,
                        size = size,
                        text = result.text,
                        status = AttachmentStatus.EXTRACTED
                    )
                    is TextExtractionResult.Failure -> {
                        log.info("Attachment '{}' extraction failed: {}", fileName, result.error)
                        AttachmentExtraction(
                            fileName = fileName,
                            contentType = contentType,
                            size = size,
                            text = "[Extraction failed: ${result.error}]",
                            status = AttachmentStatus.EXTRACTION_FAILED
                        )
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Unexpected error reading attachment '{}': {}", fileName, e.message)
            AttachmentExtraction(
                fileName = fileName,
                contentType = contentType,
                size = size,
                text = "[Extraction failed: ${e.message ?: "unknown"}]",
                status = AttachmentStatus.EXTRACTION_FAILED
            )
        }
    }

    private fun isAttachment(part: Part): Boolean {
        val disposition = runCatching { part.disposition }.getOrNull()
        return Part.ATTACHMENT.equals(disposition, ignoreCase = true)
    }

    private fun isLocateOnly(contentType: String?): Boolean {
        if (contentType == null) return false
        val lower = contentType.lowercase()
        return LOCATE_ONLY_PREFIXES.any { lower.startsWith(it) }
    }

    private fun safeFileName(part: Part): String? = runCatching { part.fileName }.getOrNull()

    private fun safeContentType(part: Part): String? =
        runCatching { part.contentType?.substringBefore(";")?.trim() }.getOrNull()

    private fun safeSize(part: Part): Long? = runCatching {
        // Part.getSize() returns -1 when the size isn't known; treat that as null.
        part.size.takeIf { it >= 0 }?.toLong()
    }.getOrNull()
}

/**
 * One attachment encountered on an email message.
 *
 * [text] is `null` for [AttachmentStatus.LOCATE_ONLY] (no body extracted),
 * a placeholder string for skipped/failed extraction, or the extracted
 * Tika text for [AttachmentStatus.EXTRACTED].
 */
data class AttachmentExtraction(
    val fileName: String,
    val contentType: String?,
    val size: Long?,
    val text: String?,
    val status: AttachmentStatus
)

enum class AttachmentStatus {
    /** Text was successfully extracted via Tika. */
    EXTRACTED,
    /** Binary type (image/video/audio) — metadata-only chunk, no body text. */
    LOCATE_ONLY,
    /** Attachment exceeded the configured max size — placeholder body. */
    SKIPPED_TOO_LARGE,
    /** Tika or stream failure — placeholder body with the error reason. */
    EXTRACTION_FAILED
}
