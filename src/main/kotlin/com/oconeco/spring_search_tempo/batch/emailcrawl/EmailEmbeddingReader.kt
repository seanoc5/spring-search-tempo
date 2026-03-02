package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.OffsetDateTime


/**
 * Pagination-based ItemReader that reads ContentChunks linked to email messages
 * for embedding generation.
 *
 * Same pagination pattern as EmailNLPReader but uses findEmailChunksForEmbedding.
 *
 * @param contentChunkRepository Repository for querying content chunks
 * @param accountId The email account ID to filter by
 * @param cutoffDate Only include chunks from messages received on or after this date
 * @param forceRefresh If true, include already-embedded chunks
 * @param pageSize Number of chunks to load per page (default 50)
 */
class EmailEmbeddingReader(
    private val contentChunkRepository: ContentChunkRepository,
    private val accountId: Long,
    private val cutoffDate: OffsetDateTime,
    private val forceRefresh: Boolean = false,
    private val pageSize: Int = 50
) : ItemReader<ContentChunk> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailEmbeddingReader::class.java)
    }

    private var currentPage = 0
    private var currentPageData: List<ContentChunk> = emptyList()
    private var currentIndex = 0
    private var totalChunksProcessed = 0
    private var initialized = false
    private var totalChunks: Long = 0

    @Synchronized
    override fun read(): ContentChunk? {
        if (!initialized) {
            initialize()
        }

        if (currentIndex >= currentPageData.size) {
            loadNextPage()

            if (currentPageData.isEmpty()) {
                log.info(
                    "EmailEmbeddingReader completed for account {}. Total chunks read: {}",
                    accountId, totalChunksProcessed
                )
                return null
            }
        }

        val chunk = currentPageData[currentIndex]
        currentIndex++
        totalChunksProcessed++

        if (totalChunksProcessed % 50 == 0) {
            log.info(
                "EmailEmbeddingReader progress for account {}: {} / {} chunks read",
                accountId, totalChunksProcessed, totalChunks
            )
        }

        return chunk
    }

    private fun initialize() {
        log.info(
            "Initializing EmailEmbeddingReader for account {} (cutoff={}, forceRefresh={})...",
            accountId, cutoffDate, forceRefresh
        )

        val firstPage = contentChunkRepository.findEmailChunksForEmbedding(
            accountId, cutoffDate, forceRefresh,
            PageRequest.of(0, pageSize, Sort.by("id"))
        )
        totalChunks = firstPage.totalElements

        log.info(
            "Found {} email chunks for embedding for account {} (cutoff={}, forceRefresh={})",
            totalChunks, accountId, cutoffDate, forceRefresh
        )

        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        log.debug("Loading page {} with page size {} for account {}", currentPage, pageSize, accountId)

        val page = contentChunkRepository.findEmailChunksForEmbedding(
            accountId, cutoffDate, forceRefresh,
            PageRequest.of(currentPage, pageSize, Sort.by("id"))
        )

        currentPageData = page.content
        currentIndex = 0
        currentPage++

        if (currentPageData.isNotEmpty()) {
            log.debug("Loaded {} chunks on page {}", currentPageData.size, currentPage - 1)
        }
    }
}
