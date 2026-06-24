package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.EmailMessageService
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
 * When [largeBodyThresholdChars] > 0 and [emailMessageService] is provided,
 * also truncates `email_message.body_text` to that many characters after
 * the message's chunks land (ADR-006 / issue #161 — the email-side
 * counterpart of the FSFile #147 fix). Truncation only runs on messages
 * whose chunks wrote successfully, so we never throw away content that
 * isn't represented in `ContentChunk`.
 *
 * @param chunkService Service for persisting ContentChunks
 * @param contentChunkRepository Repository for deleting existing chunks on forceRefresh
 * @param forceRefresh If true, delete existing chunks before writing new ones
 * @param emailMessageService Service used to bound body_text after chunking
 * @param largeBodyThresholdChars Character cap for `body_text` after
 *   chunking. 0 (or negative) disables the truncation step.
 */
class EmailChunkWriter(
    private val chunkService: ContentChunkService,
    private val contentChunkRepository: ContentChunkRepository? = null,
    private val forceRefresh: Boolean = false,
    private val emailMessageService: EmailMessageService? = null,
    private val largeBodyThresholdChars: Long = 0L
) : ItemWriter<List<ContentChunkDTO>> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailChunkWriter::class.java)
    }

    private var totalChunksSaved = 0
    private var totalBodyTextTruncated = 0

    override fun write(chunk: Chunk<out List<ContentChunkDTO>>) {
        var batchChunksSaved = 0
        val processedEmailIds = mutableSetOf<Long>()

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
            val emailIdsThisList = chunkList.mapNotNull { it.emailMessage }.toSet()
            try {
                val ids = chunkService.createBulk(chunkList)
                batchChunksSaved += ids.size
                totalChunksSaved += ids.size
                processedEmailIds.addAll(emailIdsThisList)
            } catch (e: Exception) {
                log.warn("Bulk chunk save failed for email, falling back to per-item: {}", e.message)
                var perItemSavedCount = 0
                chunkList.forEach { chunkDTO ->
                    try {
                        chunkService.create(chunkDTO)
                        batchChunksSaved++
                        totalChunksSaved++
                        perItemSavedCount++
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
                // Only mark these emails as "fully chunked" — and thus
                // eligible for body_text truncation — when EVERY chunk
                // landed. A partial save means some content is still
                // only in body_text; truncating would lose it.
                if (perItemSavedCount == chunkList.size) {
                    processedEmailIds.addAll(emailIdsThisList)
                } else {
                    log.warn(
                        "Partial chunk save for email(s) {}: {}/{} chunks saved; " +
                            "skipping body_text truncation to preserve missing content.",
                        emailIdsThisList, perItemSavedCount, chunkList.size
                    )
                }
            }
        }

        // Truncate body_text for messages whose chunks landed cleanly so
        // we don't keep a multi-MB row around when the full content is
        // already represented in ContentChunk (ADR-006 / issue #161).
        if (emailMessageService != null && largeBodyThresholdChars > 0) {
            processedEmailIds.forEach { emailId ->
                try {
                    val dropped = emailMessageService.truncateBodyTextToThreshold(
                        emailId, largeBodyThresholdChars
                    )
                    if (dropped > 0) {
                        totalBodyTextTruncated++
                        log.debug(
                            "Truncated body_text for email {}: dropped {} chars (kept first {})",
                            emailId, dropped, largeBodyThresholdChars
                        )
                    }
                } catch (e: Exception) {
                    log.warn("Failed to truncate body_text for email {}: {}", emailId, e.message)
                }
            }
        }

        if (batchChunksSaved > 0) {
            log.debug("EmailChunkWriter: Saved {} chunks (total: {})", batchChunksSaved, totalChunksSaved)
        }
    }
}
