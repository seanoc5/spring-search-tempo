package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.OffsetDateTime


/**
 * ItemReader that reads "interesting" EmailMessageDTO entities for chunking.
 *
 * Uses the "interesting" filter: messages within cutoff date, not junk-tagged,
 * with non-null bodyText, and optionally not already chunked (unless forceRefresh).
 *
 * @param emailMessageService Service for accessing EmailMessage entities
 * @param accountId The account ID to filter messages by
 * @param cutoffDate Only include messages received on or after this date
 * @param forceRefresh If true, include messages that already have chunks
 * @param pageSize Number of messages to load per page (default 50)
 */
class EmailChunkReader(
    private val emailMessageService: EmailMessageService,
    private val accountId: Long,
    private val cutoffDate: OffsetDateTime,
    private val forceRefresh: Boolean = false,
    private val pageSize: Int = 50
) : ItemReader<EmailMessageDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailChunkReader::class.java)
    }

    private var currentPage = 0
    private var currentPageData: List<EmailMessageDTO> = emptyList()
    private var currentIndex = 0
    private var totalMessagesProcessed = 0
    private var initialized = false
    private var totalMessages: Long = 0

    override fun read(): EmailMessageDTO? {
        if (!initialized) {
            initialize()
        }

        // If we've exhausted the current page, load the next one
        if (currentIndex >= currentPageData.size) {
            loadNextPage()

            // If still no data, we're done
            if (currentPageData.isEmpty()) {
                log.info(
                    "EmailChunkReader completed for account {}. Total messages processed: {}",
                    accountId,
                    totalMessagesProcessed
                )
                return null
            }
        }

        val message = currentPageData[currentIndex]
        currentIndex++
        totalMessagesProcessed++

        if (totalMessagesProcessed % 50 == 0) {
            log.info(
                "EmailChunkReader progress for account {}: {} messages processed",
                accountId,
                totalMessagesProcessed
            )
        }

        return message
    }

    private fun initialize() {
        log.info(
            "Initializing EmailChunkReader for account {} (cutoff={}, forceRefresh={})...",
            accountId, cutoffDate, forceRefresh
        )

        // Get first page to determine total count
        val firstPage = emailMessageService.findInterestingForChunking(
            accountId,
            cutoffDate,
            forceRefresh,
            PageRequest.of(0, pageSize, Sort.by("id"))
        )
        totalMessages = firstPage.totalElements

        log.info(
            "Found {} interesting messages to chunk for account {} (cutoff={}, forceRefresh={})",
            totalMessages, accountId, cutoffDate, forceRefresh
        )

        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        log.debug("Loading page {} with page size {} for account {}", currentPage, pageSize, accountId)

        val page = emailMessageService.findInterestingForChunking(
            accountId,
            cutoffDate,
            forceRefresh,
            PageRequest.of(currentPage, pageSize, Sort.by("id"))
        )

        currentPageData = page.content
        currentIndex = 0
        currentPage++

        if (currentPageData.isNotEmpty()) {
            log.debug("Loaded {} messages on page {}", currentPageData.size, currentPage - 1)
        } else {
            log.debug("No more messages to load")
        }
    }
}
