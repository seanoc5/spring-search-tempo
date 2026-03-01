package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.OffsetDateTime


/**
 * Pagination-based ItemReader that reads ContentChunks linked to "interesting"
 * email messages for NLP processing.
 *
 * Applies the same "interesting" filter as chunking: messages within cutoff date,
 * not junk-tagged, with non-null chunk text, and optionally not already NLP-processed.
 *
 * @param contentChunkRepository Repository for querying content chunks
 * @param accountId The email account ID to filter by
 * @param cutoffDate Only include chunks from messages received on or after this date
 * @param forceRefresh If true, include already NLP-processed chunks
 * @param pageSize Number of chunks to load per page (default 50)
 */
class EmailNLPReader(
    private val contentChunkRepository: ContentChunkRepository,
    private val accountId: Long,
    private val cutoffDate: OffsetDateTime,
    private val forceRefresh: Boolean = false,
    private val pageSize: Int = 50
) : ItemReader<ContentChunk> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailNLPReader::class.java)
    }

    private var currentPage = 0
    private var currentPageData: List<ContentChunk> = emptyList()
    private var currentIndex = 0
    private var totalChunksProcessed = 0
    private var initialized = false
    private var totalChunks: Long = 0

    override fun read(): ContentChunk? {
        if (!initialized) {
            initialize()
        }

        // If we've exhausted the current page, load the next one
        if (currentIndex >= currentPageData.size) {
            loadNextPage()

            // If still no data, we're done
            if (currentPageData.isEmpty()) {
                log.info(
                    "EmailNLPReader completed for account {}. Total chunks read: {}",
                    accountId,
                    totalChunksProcessed
                )
                return null
            }
        }

        val chunk = currentPageData[currentIndex]
        currentIndex++
        totalChunksProcessed++

        if (totalChunksProcessed % 50 == 0) {
            log.info(
                "EmailNLPReader progress for account {}: {} chunks read",
                accountId,
                totalChunksProcessed
            )
        }

        return chunk
    }

    private fun initialize() {
        log.info(
            "Initializing EmailNLPReader for account {} (cutoff={}, forceRefresh={})...",
            accountId, cutoffDate, forceRefresh
        )

        val firstPage = contentChunkRepository.findEmailChunksForNlp(
            accountId,
            cutoffDate,
            forceRefresh,
            PageRequest.of(0, pageSize, Sort.by("id"))
        )
        totalChunks = firstPage.totalElements

        log.info(
            "Found {} email chunks for NLP processing for account {} (cutoff={}, forceRefresh={})",
            totalChunks, accountId, cutoffDate, forceRefresh
        )

        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        log.debug("Loading page {} with page size {} for account {}", currentPage, pageSize, accountId)

        val page = contentChunkRepository.findEmailChunksForNlp(
            accountId,
            cutoffDate,
            forceRefresh,
            PageRequest.of(currentPage, pageSize, Sort.by("id"))
        )

        currentPageData = page.content
        currentIndex = 0
        currentPage++

        if (currentPageData.isNotEmpty()) {
            log.debug("Loaded {} chunks on page {}", currentPageData.size, currentPage - 1)
        } else {
            log.debug("No more chunks to load")
        }
    }
}
