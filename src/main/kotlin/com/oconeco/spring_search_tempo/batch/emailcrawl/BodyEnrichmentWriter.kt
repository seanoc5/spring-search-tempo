package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailMessageService
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter


/**
 * Pass 2 Writer: Updates messages with body content and marks as COMPLETE.
 */
class BodyEnrichmentWriter(
    private val emailMessageService: EmailMessageService
) : ItemWriter<BodyEnrichmentResult> {

    companion object {
        private val log = LoggerFactory.getLogger(BodyEnrichmentWriter::class.java)
    }

    private var savedCount = 0

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
            } catch (e: Exception) {
                log.error("Failed to save body for message {}: {}", result.messageId, e.message, e)
            }
        }

        if (savedCount % 100 == 0 && savedCount > 0) {
            log.info("Saved {} message bodies", savedCount)
        }
    }

    fun getSavedCount(): Int = savedCount
}
