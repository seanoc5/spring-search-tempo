package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.OneDriveFetchStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.OneDriveItemDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor


/**
 * Pass 1 Processor: Maps Graph DriveItem data to OneDriveItemDTO.
 *
 * Handles:
 * - Mapping DriveItem fields (name, path, size, dates, hash)
 * - Setting URI as "onedrive://{driveId}/{graphItemId}"
 * - Determining analysisStatus based on file type (folders = LOCATE, files = INDEX)
 * - Setting fetchStatus = METADATA_ONLY
 * - Flagging deleted items
 */
class OneDriveMetadataProcessor(
    private val accountId: Long
) : ItemProcessor<GraphDriveItemWrapper, OneDriveItemDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveMetadataProcessor::class.java)
    }

    private var processedCount = 0

    override fun process(item: GraphDriveItemWrapper): OneDriveItemDTO? {
        val driveItem = item.driveItem

        try {
            val graphItemId = driveItem.id ?: return null

            processedCount++
            if (processedCount % 100 == 0) {
                log.info("Processed {} OneDrive items", processedCount)
            }

            val dto = OneDriveItemDTO().apply {
                this.graphItemId = graphItemId
                this.driveId = item.driveId
                this.oneDriveAccount = item.accountId
                this.itemName = driveItem.name
                this.itemPath = driveItem.parentReference?.path?.let { parentPath ->
                    if (driveItem.name != null) "$parentPath/${driveItem.name}" else parentPath
                }
                this.size = driveItem.size
                this.isFolder = driveItem.folder != null
                this.isDeleted = item.isDeleted

                // Parent reference
                this.parentGraphItemId = driveItem.parentReference?.id

                // File hash
                if (driveItem.file?.hashes?.sha256Hash != null) {
                    this.fileHash = driveItem.file!!.hashes!!.sha256Hash
                    this.hashAlgorithm = "SHA256"
                } else if (driveItem.file?.hashes?.quickXorHash != null) {
                    this.fileHash = driveItem.file!!.hashes!!.quickXorHash
                    this.hashAlgorithm = "quickXor"
                }

                // MIME type
                this.mimeType = driveItem.file?.mimeType

                // Graph timestamps (already OffsetDateTime from Graph SDK)
                this.graphCreatedAt = driveItem.createdDateTime
                this.graphModifiedAt = driveItem.lastModifiedDateTime

                // Standard fields
                this.uri = "onedrive://${item.driveId}/$graphItemId"
                this.label = driveItem.name
                this.status = Status.NEW
                this.version = 0L

                // Analysis status: folders get LOCATE, files get INDEX
                this.analysisStatus = if (this.isFolder) {
                    AnalysisStatus.LOCATE
                } else {
                    determineAnalysisStatus(this.mimeType, this.itemName)
                }

                // Mark as metadata-only - content download happens in Pass 2
                this.fetchStatus = OneDriveFetchStatus.METADATA_ONLY
            }

            return dto

        } catch (e: Exception) {
            log.error("Error processing OneDrive item {}: {}", driveItem.id, e.message, e)
            return null
        }
    }

    /**
     * Determine analysis status based on MIME type and file name.
     * Most files get INDEX (full text extraction). Binary/media files get LOCATE.
     */
    private fun determineAnalysisStatus(mimeType: String?, fileName: String?): AnalysisStatus {
        if (mimeType == null) return AnalysisStatus.INDEX

        return when {
            // Media files - metadata only
            mimeType.startsWith("image/") -> AnalysisStatus.LOCATE
            mimeType.startsWith("video/") -> AnalysisStatus.LOCATE
            mimeType.startsWith("audio/") -> AnalysisStatus.LOCATE

            // Binary/archive files - metadata only
            mimeType == "application/zip" -> AnalysisStatus.LOCATE
            mimeType == "application/x-tar" -> AnalysisStatus.LOCATE
            mimeType == "application/x-gzip" -> AnalysisStatus.LOCATE
            mimeType == "application/x-7z-compressed" -> AnalysisStatus.LOCATE
            mimeType == "application/x-rar-compressed" -> AnalysisStatus.LOCATE
            mimeType == "application/octet-stream" -> AnalysisStatus.LOCATE

            // Everything else (documents, text, code) - full text extraction
            else -> AnalysisStatus.INDEX
        }
    }

    fun getProcessedCount(): Int = processedCount
}
