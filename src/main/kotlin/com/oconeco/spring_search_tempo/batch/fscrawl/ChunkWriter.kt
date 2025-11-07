package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.ContentChunksService
import com.oconeco.spring_search_tempo.base.model.ContentChunksDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter

/**
 * ItemWriter that saves ContentChunks to the database.
 *
 * Processes lists of chunks (from ChunkProcessor) and saves them using
 * the ContentChunksService.
 *
 * @param chunkService Service for persisting ContentChunks
 */
class ChunkWriter(
    private val chunkService: ContentChunksService
) : ItemWriter<List<ContentChunksDTO>> {

    companion object {
        private val log = LoggerFactory.getLogger(ChunkWriter::class.java)
    }

    private var totalChunksSaved = 0

    override fun write(chunk: Chunk<out List<ContentChunksDTO>>) {
        var batchChunksSaved = 0

        chunk.items.forEach { chunkList ->
            // Each item is a list of chunks from a single file
            chunkList.forEach { chunkDTO ->
                try {
                    chunkService.create(chunkDTO)
                    batchChunksSaved++
                    totalChunksSaved++
                } catch (e: Exception) {
                    log.error(
                        "Error saving chunk {} for file {}: {}",
                        chunkDTO.chunkNumber,
                        chunkDTO.concept,
                        e.message,
                        e
                    )
                }
            }
        }

        if (batchChunksSaved > 0) {
            log.info("ChunkWriter: Saved {} chunks (total: {})", batchChunksSaved, totalChunksSaved)
        }
    }
}
