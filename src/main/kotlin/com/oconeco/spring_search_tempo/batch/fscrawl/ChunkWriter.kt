package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter

/**
 * ItemWriter that saves ContentChunk to the database.
 *
 * Processes lists of chunks (from ChunkProcessor) and saves them using
 * the ContentChunkService. After successfully writing chunks for a file,
 * marks the file as chunked by setting its chunkedAt timestamp.
 *
 * @param chunkService Service for persisting ContentChunks
 * @param fileService Service for updating file chunkedAt timestamp
 */
class ChunkWriter(
    private val chunkService: ContentChunkService,
    private val fileService: FSFileService? = null
) : ItemWriter<List<ContentChunkDTO>> {

    companion object {
        private val log = LoggerFactory.getLogger(ChunkWriter::class.java)
    }

    private var totalChunksSaved = 0
    private var totalFilesMarked = 0

    override fun write(chunk: Chunk<out List<ContentChunkDTO>>) {
        var batchChunksSaved = 0
        val processedFileIds = mutableSetOf<Long>()

        chunk.items.forEach { chunkList ->
            // Each item is a list of chunks from a single file
            var fileChunksSucceeded = true
            var fileId: Long? = null

            chunkList.forEach { chunkDTO ->
                try {
                    chunkService.create(chunkDTO)
                    batchChunksSaved++
                    totalChunksSaved++
                    fileId = chunkDTO.concept
                } catch (e: Exception) {
                    log.error(
                        "Error saving chunk {} for file {}: {}",
                        chunkDTO.chunkNumber,
                        chunkDTO.concept,
                        e.message,
                        e
                    )
                    fileChunksSucceeded = false
                }
            }

            // Mark file as chunked if all chunks saved successfully
            if (fileChunksSucceeded && fileId != null) {
                processedFileIds.add(fileId!!)
            }
        }

        // Update chunkedAt for successfully processed files
        if (fileService != null) {
            processedFileIds.forEach { fileId ->
                try {
                    fileService.markAsChunked(fileId)
                    totalFilesMarked++
                } catch (e: Exception) {
                    log.warn("Failed to mark file {} as chunked: {}", fileId, e.message)
                }
            }
        }

        if (batchChunksSaved > 0) {
            log.debug("ChunkWriter: Saved {} chunks for {} files (total chunks: {}, total files marked: {})",
                batchChunksSaved, processedFileIds.size, totalChunksSaved, totalFilesMarked)
        }
    }
}
