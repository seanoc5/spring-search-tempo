package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort


/**
 * ItemReader that reads EmailMessageDTO entities with non-null bodyText for chunking.
 *
 * Uses service layer pagination to efficiently process messages in batches.
 *
 * @param emailMessageService Service for accessing EmailMessage entities
 * @param accountId The account ID to filter messages by
 * @param pageSize Number of messages to load per page (default 50)
 */
class EmailChunkReader(
    private val emailMessageService: EmailMessageService,
    private val accountId: Long,
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
        log.info("Initializing EmailChunkReader for account {}...", accountId)

        // Get first page to determine total count
        val firstPage = emailMessageService.findMessagesWithBodyTextByAccount(
            accountId,
            PageRequest.of(0, pageSize, Sort.by("id"))
        )
        totalMessages = firstPage.totalElements

        log.info("Found {} messages with bodyText to chunk for account {}", totalMessages, accountId)

        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        log.debug("Loading page {} with page size {} for account {}", currentPage, pageSize, accountId)

        val page = emailMessageService.findMessagesWithBodyTextByAccount(
            accountId,
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
