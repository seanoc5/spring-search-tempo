package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.service.AttachmentExtraction
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter


/**
 * Pass 2 Writer: Updates messages with body content, then persists any
 * attachment extractions as `ContentChunk` rows linked to the email message.
 *
 * Attachment chunks are tagged `chunkType = "EmailAttachment"` and carry the
 * original filename in `sourceFileName`. FTS picks them up automatically via
 * the existing GIN index on `content_chunks.fts_vector`.
 */
class BodyEnrichmentWriter(
    private val emailMessageService: EmailMessageService,
    private val contentChunkService: ContentChunkService
) : ItemWriter<BodyEnrichmentResult> {

    companion object {
        private val log = LoggerFactory.getLogger(BodyEnrichmentWriter::class.java)
        const val ATTACHMENT_CHUNK_TYPE = "EmailAttachment"
    }

    private var savedCount = 0
    private var attachmentChunkCount = 0

    override fun write(chunk: Chunk<out BodyEnrichmentResult>) {
        chunk.items.forEach { result ->
            try {
                emailMessageService.updateBodyAndComplete(
                    id = result.messageId,
                    bodyText = result.bodyText,
                    bodySize = result.bodySize,
                    hasAttachments = result.hasAttachments,
                    attachmentCount = result.attachmentCount,
                    attachmentNames = result.attachmentNames
                )
                savedCount++

                if (result.attachmentExtractions.isNotEmpty()) {
                    persistAttachmentChunks(result.messageId, result.attachmentExtractions)
                }
            } catch (e: Exception) {
                log.error("Failed to save body for message {}: {}", result.messageId, e.message, e)
            }
        }

        if (savedCount % 100 == 0 && savedCount > 0) {
            log.info("Saved {} message bodies ({} attachment chunks)", savedCount, attachmentChunkCount)
        }
    }

    private fun persistAttachmentChunks(messageId: Long, extractions: List<AttachmentExtraction>) {
        val dtos = extractions.mapIndexed { index, extraction ->
            ContentChunkDTO().apply {
                this.emailMessage = messageId
                this.chunkType = ATTACHMENT_CHUNK_TYPE
                this.chunkNumber = index
                this.sourceFileName = extraction.fileName
                this.text = extraction.text ?: ""
                this.textLength = (extraction.text?.length ?: 0).toLong()
                this.status = extraction.status.name
            }
        }

        try {
            val ids = contentChunkService.createBulk(dtos)
            attachmentChunkCount += ids.size
            log.debug("Persisted {} attachment chunks for message {}", ids.size, messageId)
        } catch (e: Exception) {
            log.error("Failed to persist {} attachment chunks for message {}: {}",
                dtos.size, messageId, e.message, e)
        }
    }

    fun getSavedCount(): Int = savedCount
    fun getAttachmentChunkCount(): Int = attachmentChunkCount
}
