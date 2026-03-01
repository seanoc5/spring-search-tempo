package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter


/**
 * ItemWriter that saves ContentChunks for email messages to the database.
 *
 * Processes lists of chunks (from EmailChunkProcessor) and saves them using
 * the ContentChunkService. Uses bulk createBulk() for efficiency, with
 * per-item fallback on failure.
 *
 * When forceRefresh is true, deletes existing chunks for each email message
 * before writing new ones.
 *
 * @param chunkService Service for persisting ContentChunks
 * @param contentChunkRepository Repository for deleting existing chunks on forceRefresh
 * @param forceRefresh If true, delete existing chunks before writing new ones
 */
class EmailChunkWriter(
    private val chunkService: ContentChunkService,
    private val contentChunkRepository: ContentChunkRepository? = null,
    private val forceRefresh: Boolean = false
) : ItemWriter<List<ContentChunkDTO>> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailChunkWriter::class.java)
    }

    private var totalChunksSaved = 0

    override fun write(chunk: Chunk<out List<ContentChunkDTO>>) {
        var batchChunksSaved = 0

        // When forceRefresh, delete existing chunks for each email before writing
        if (forceRefresh && contentChunkRepository != null) {
            val emailMessageIds = chunk.items
                .flatMap { it }
                .mapNotNull { it.emailMessage }
                .distinct()

            emailMessageIds.forEach { emailMessageId ->
                val deleted = contentChunkRepository.deleteByEmailMessageId(emailMessageId)
                if (deleted > 0) {
                    log.debug("Deleted {} existing chunks for email message {} (forceRefresh)", deleted, emailMessageId)
                }
            }
        }

        chunk.items.forEach { chunkList ->
            try {
                val ids = chunkService.createBulk(chunkList)
                batchChunksSaved += ids.size
                totalChunksSaved += ids.size
            } catch (e: Exception) {
                log.warn("Bulk chunk save failed for email, falling back to per-item: {}", e.message)
                chunkList.forEach { chunkDTO ->
                    try {
                        chunkService.create(chunkDTO)
                        batchChunksSaved++
                        totalChunksSaved++
                    } catch (e2: Exception) {
                        log.error(
                            "Error saving chunk {} for email {}: {}",
                            chunkDTO.chunkNumber,
                            chunkDTO.emailMessage,
                            e2.message,
                            e2
                        )
                    }
                }
            }
        }

        if (batchChunksSaved > 0) {
            log.debug("EmailChunkWriter: Saved {} chunks (total: {})", batchChunksSaved, totalChunksSaved)
        }
    }
}
