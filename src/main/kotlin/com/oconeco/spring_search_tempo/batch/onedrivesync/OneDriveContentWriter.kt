package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.OneDriveItemService
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter


/**
 * Pass 2 Writer: Updates OneDrive items with downloaded content and marks as COMPLETE.
 *
 * - Success: calls updateContentAndComplete
 * - Failure: calls markDownloadFailed
 */
class OneDriveContentWriter(
    private val oneDriveItemService: OneDriveItemService
) : ItemWriter<OneDriveContentResult> {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveContentWriter::class.java)
    }

    private var savedCount = 0
    private var failedCount = 0

    override fun write(chunk: Chunk<out OneDriveContentResult>) {
        chunk.items.forEach { result ->
            try {
                if (result.failed) {
                    oneDriveItemService.markDownloadFailed(result.itemId, result.errorMessage)
                    failedCount++
                } else {
                    oneDriveItemService.updateContentAndComplete(
                        id = result.itemId,
                        bodyText = result.bodyText,
                        bodySize = result.bodySize,
                        contentType = result.contentType,
                        author = result.author,
                        title = result.title,
                        pageCount = result.pageCount
                    )
                    savedCount++
                }
            } catch (e: Exception) {
                log.error("Failed to save content for OneDrive item {}: {}", result.itemId, e.message, e)
            }
        }

        if (savedCount % 50 == 0 && savedCount > 0) {
            log.info("OneDrive content writer: {} saved, {} failed", savedCount, failedCount)
        }
    }

    fun getSavedCount(): Int = savedCount
    fun getFailedCount(): Int = failedCount
}
