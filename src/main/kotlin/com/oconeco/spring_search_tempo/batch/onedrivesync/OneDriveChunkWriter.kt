package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.OneDriveItemService
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter


/**
 * Pass 3 Writer: Saves ContentChunks for OneDrive items and marks items as chunked.
 */
class OneDriveChunkWriter(
    private val chunkService: ContentChunkService,
    private val oneDriveItemService: OneDriveItemService
) : ItemWriter<List<ContentChunkDTO>> {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveChunkWriter::class.java)
    }

    private var totalChunksSaved = 0

    override fun write(chunk: Chunk<out List<ContentChunkDTO>>) {
        var batchChunksSaved = 0

        chunk.items.forEach { chunkList ->
            val oneDriveItemId = chunkList.firstOrNull()?.oneDriveItem

            chunkList.forEach { chunkDTO ->
                try {
                    chunkService.create(chunkDTO)
                    batchChunksSaved++
                    totalChunksSaved++
                } catch (e: Exception) {
                    log.error("Error saving chunk {} for OneDrive item {}: {}",
                        chunkDTO.chunkNumber, chunkDTO.oneDriveItem, e.message, e)
                }
            }

            // Mark the OneDrive item as chunked
            if (oneDriveItemId != null) {
                try {
                    oneDriveItemService.markAsChunked(oneDriveItemId)
                } catch (e: Exception) {
                    log.error("Failed to mark OneDrive item {} as chunked: {}",
                        oneDriveItemId, e.message, e)
                }
            }
        }

        if (batchChunksSaved > 0) {
            log.debug("OneDriveChunkWriter: Saved {} chunks (total: {})", batchChunksSaved, totalChunksSaved)
        }
    }
}
