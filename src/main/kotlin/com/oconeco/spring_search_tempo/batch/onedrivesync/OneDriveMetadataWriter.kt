package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.OneDriveItemService
import com.oconeco.spring_search_tempo.base.model.OneDriveItemDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter


/**
 * Pass 1 Writer: Persists OneDrive item metadata to the database.
 *
 * - Deleted items: calls markAsDeleted (soft delete)
 * - Other items: calls upsertFromGraphItem (insert or update)
 */
class OneDriveMetadataWriter(
    private val oneDriveItemService: OneDriveItemService
) : ItemWriter<OneDriveItemDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveMetadataWriter::class.java)
    }

    private var upsertCount = 0
    private var deleteCount = 0

    override fun write(chunk: Chunk<out OneDriveItemDTO>) {
        chunk.items.forEach { dto ->
            try {
                if (dto.isDeleted) {
                    val graphItemId = dto.graphItemId
                    val driveId = dto.driveId
                    if (graphItemId != null && driveId != null) {
                        oneDriveItemService.markAsDeleted(graphItemId, driveId)
                        deleteCount++
                    }
                } else {
                    oneDriveItemService.upsertFromGraphItem(dto)
                    upsertCount++
                }
            } catch (e: Exception) {
                log.error("Failed to write OneDrive item {}: {}", dto.graphItemId, e.message, e)
            }
        }

        if ((upsertCount + deleteCount) % 100 == 0 && (upsertCount + deleteCount) > 0) {
            log.info("OneDrive metadata writer: {} upserted, {} deleted", upsertCount, deleteCount)
        }
    }

    fun getUpsertCount(): Int = upsertCount
    fun getDeleteCount(): Int = deleteCount
}
