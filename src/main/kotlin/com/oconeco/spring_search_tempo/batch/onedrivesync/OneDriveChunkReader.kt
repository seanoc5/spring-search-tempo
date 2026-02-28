package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.OneDriveItemService
import com.oconeco.spring_search_tempo.base.model.OneDriveItemDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort


/**
 * Pass 3 Reader: Reads OneDrive items that have body text but haven't been chunked.
 *
 * Queries for items where bodyText IS NOT NULL AND chunkedAt IS NULL AND isFolder = false.
 */
class OneDriveChunkReader(
    private val oneDriveItemService: OneDriveItemService,
    private val accountId: Long,
    private val pageSize: Int = 50
) : ItemReader<OneDriveItemDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveChunkReader::class.java)
    }

    private var currentPage = 0
    private var currentPageData: List<OneDriveItemDTO> = emptyList()
    private var currentIndex = 0
    private var totalRead = 0
    private var initialized = false

    override fun read(): OneDriveItemDTO? {
        if (!initialized) {
            initialize()
        }

        // If we've exhausted the current page, load the next one
        if (currentIndex >= currentPageData.size) {
            loadNextPage()

            if (currentPageData.isEmpty()) {
                log.info("OneDriveChunkReader completed for account {}. Total read: {}", accountId, totalRead)
                return null
            }
        }

        val item = currentPageData[currentIndex]
        currentIndex++
        totalRead++

        if (totalRead % 50 == 0) {
            log.info("OneDriveChunkReader progress for account {}: {} items read", accountId, totalRead)
        }

        return item
    }

    private fun initialize() {
        initialized = true

        val count = oneDriveItemService.countUnchunkedByAccount(accountId)
        log.info("Found {} OneDrive items needing chunking for account {}", count, accountId)

        loadNextPage()
    }

    private fun loadNextPage() {
        val pageable = PageRequest.of(currentPage, pageSize, Sort.by("id"))
        val page = oneDriveItemService.findUnchunkedByAccount(accountId, pageable)

        currentPageData = page.content
        currentIndex = 0
        currentPage++

        if (currentPageData.isNotEmpty()) {
            log.debug("Loaded {} OneDrive items on page {}", currentPageData.size, currentPage - 1)
        }
    }
}
