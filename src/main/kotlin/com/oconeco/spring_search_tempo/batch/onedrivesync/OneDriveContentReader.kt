package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.OneDriveItemService
import com.oconeco.spring_search_tempo.base.model.OneDriveItemDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort


/**
 * Pass 2 Reader: Reads OneDrive items needing content download from the database.
 *
 * Queries for items with fetchStatus = METADATA_ONLY that are not folders
 * and have analysisStatus of INDEX or ANALYZE.
 */
class OneDriveContentReader(
    private val oneDriveItemService: OneDriveItemService,
    private val accountId: Long,
    private val pageSize: Int = 50
) : ItemReader<OneDriveItemDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveContentReader::class.java)
    }

    private var items: MutableList<OneDriveItemDTO>? = null
    private var currentIndex = 0
    private var initialized = false

    override fun read(): OneDriveItemDTO? {
        if (!initialized) {
            initialize()
        }

        val list = items ?: return null
        if (currentIndex >= list.size) {
            return null
        }

        return list[currentIndex++]
    }

    private fun initialize() {
        initialized = true

        val count = oneDriveItemService.countMetadataOnlyForDownload(accountId)
        log.info("Found {} OneDrive items needing content download for account {}", count, accountId)

        if (count == 0L) {
            items = mutableListOf()
            return
        }

        // Load all items needing content download
        val allItems = mutableListOf<OneDriveItemDTO>()
        var page = 0
        var hasMore = true

        while (hasMore) {
            val pageable = PageRequest.of(page, pageSize, Sort.by("id"))
            val pageResult = oneDriveItemService.findMetadataOnlyForDownload(accountId, pageable)
            allItems.addAll(pageResult.content)
            hasMore = pageResult.hasNext()
            page++
        }

        items = allItems
        log.info("Loaded {} OneDrive items for content download", allItems.size)
    }
}
