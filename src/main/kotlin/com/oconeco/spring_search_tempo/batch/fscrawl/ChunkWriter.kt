package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * ItemWriter that saves ContentChunk to the database.
 *
 * Processes lists of chunks (from ChunkProcessor) and saves them using
 * the ContentChunkService. After successfully writing chunks for a file,
 * marks the file as chunked by setting its chunkedAt timestamp.
 *
 * Uses bulk createBulk() for efficiency, with per-item fallback on failure.
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

    private val totalChunksSaved = AtomicInteger(0)
    private val totalFilesMarked = AtomicInteger(0)

    override fun write(chunk: Chunk<out List<ContentChunkDTO>>) {
        var batchChunksSaved = 0
        val processedFileIds = mutableSetOf<Long>()

        chunk.items.forEach { chunkList ->
            // Each item is a list of chunks from a single file
            val fileId = chunkList.firstOrNull()?.concept

            try {
                val ids = chunkService.createBulk(chunkList)
                batchChunksSaved += ids.size
                totalChunksSaved.addAndGet(ids.size)
                if (fileId != null) processedFileIds.add(fileId)
            } catch (e: Exception) {
                log.warn("Bulk chunk save failed for file {}, falling back to per-item: {}", fileId, e.message)
                var fileChunksSucceeded = true
                chunkList.forEach { chunkDTO ->
                    try {
                        chunkService.create(chunkDTO)
                        batchChunksSaved++
                        totalChunksSaved.incrementAndGet()
                    } catch (e2: Exception) {
                        log.error(
                            "Error saving chunk {} for file {}: {}",
                            chunkDTO.chunkNumber,
                            chunkDTO.concept,
                            e2.message,
                            e2
                        )
                        fileChunksSucceeded = false
                    }
                }
                if (fileChunksSucceeded && fileId != null) {
                    processedFileIds.add(fileId)
                }
            }
        }

        // Update chunkedAt for successfully processed files
        if (fileService != null) {
            processedFileIds.forEach { fileId ->
                try {
                    fileService.markAsChunked(fileId)
                    totalFilesMarked.incrementAndGet()
                } catch (e: Exception) {
                    log.warn("Failed to mark file {} as chunked: {}", fileId, e.message)
                }
            }
        }

        if (batchChunksSaved > 0) {
            log.debug("ChunkWriter: Saved {} chunks for {} files (total chunks: {}, total files marked: {})",
                batchChunksSaved, processedFileIds.size, totalChunksSaved.get(), totalFilesMarked.get())
        }
    }
}
