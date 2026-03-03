package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.batch.chunking.Chunk
import com.oconeco.spring_search_tempo.batch.chunking.ChunkMetadata
import com.oconeco.spring_search_tempo.batch.chunking.ChunkingStrategy
import com.oconeco.spring_search_tempo.batch.chunking.ChunkingStrategySelector
import com.oconeco.spring_search_tempo.batch.chunking.SentenceChunker
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.util.concurrent.atomic.AtomicInteger

/**
 * ItemProcessor that splits FSFileDTO bodyText into ContentChunk.
 *
 * Uses pluggable ChunkingStrategy to select the appropriate chunking
 * algorithm based on content type:
 * - Code files: function/class boundaries
 * - Markdown/HTML: paragraph boundaries
 * - Default: sentence boundaries
 *
 * Processing steps:
 * 1. Select appropriate ChunkingStrategy based on content type
 * 2. Split bodyText using the selected strategy
 * 3. Convert Chunk objects to ContentChunkDTO
 * 4. Link chunks to their parent FSFile (concept)
 *
 * @param strategySelector Service that selects the chunking strategy (optional, falls back to SentenceChunker)
 * @return List of ContentChunkDTO (or null if no bodyText)
 */
class ChunkProcessor(
    private val strategySelector: ChunkingStrategySelector? = null
) : ItemProcessor<FSFileDTO, List<ContentChunkDTO>> {

    companion object {
        private val log = LoggerFactory.getLogger(ChunkProcessor::class.java)
    }

    // Fallback sentence chunker for when no selector is available
    private val fallbackChunker: ChunkingStrategy by lazy { SentenceChunker() }

    private val totalChunksCreated = AtomicInteger(0)
    private val strategyStats = mutableMapOf<String, AtomicInteger>()

    override fun process(item: FSFileDTO): List<ContentChunkDTO>? {
        val bodyText = item.bodyText
        if (bodyText.isNullOrBlank()) {
            log.debug("Skipping file {} - no bodyText", item.uri)
            return null
        }

        try {
            val fileId = item.id ?: run {
                log.warn("Skipping file {} - no id", item.uri)
                return null
            }

            // Build metadata for strategy selection
            val metadata = ChunkMetadata(
                fileId = fileId,
                contentType = item.contentType,
                fileExtension = extractExtension(item.uri),
                uri = item.uri,
                fileSize = item.bodySize
            )

            // Select appropriate chunking strategy
            val strategy = strategySelector?.selectStrategy(metadata) ?: fallbackChunker

            // Track strategy usage
            strategyStats.computeIfAbsent(strategy.name) { AtomicInteger(0) }.incrementAndGet()

            // Perform chunking
            val chunks = strategy.chunk(bodyText, metadata)

            if (chunks.isEmpty()) {
                log.debug("No chunks created for file {} (text length: {})", item.uri, bodyText.length)
                return null
            }

            // Convert to DTOs
            val dtos = chunks.map { chunk -> toDTO(chunk, fileId) }

            val newTotal = totalChunksCreated.addAndGet(dtos.size)
            if (newTotal % 100 < dtos.size) {
                log.info("ChunkProcessor progress: {} chunks created", newTotal)
            }

            log.debug(
                "Created {} chunks for file {} using {} strategy",
                dtos.size, item.uri, strategy.name
            )
            return dtos

        } catch (e: Exception) {
            log.error("Error processing file {}: {}", item.uri, e.message, e)
            return null
        }
    }

    /**
     * Converts a Chunk to ContentChunkDTO.
     */
    private fun toDTO(chunk: Chunk, fileId: Long): ContentChunkDTO {
        return ContentChunkDTO().apply {
            this.text = chunk.text
            this.concept = fileId
            this.chunkNumber = chunk.chunkNumber
            this.startPosition = chunk.startPosition
            this.endPosition = chunk.endPosition
            this.textLength = chunk.text.length.toLong()
            this.chunkType = chunk.chunkType
            this.status = "CHUNKED"
        }
    }

    /**
     * Extracts file extension from URI.
     */
    private fun extractExtension(uri: String?): String? {
        if (uri == null) return null
        val lastDot = uri.lastIndexOf('.')
        val lastSlash = uri.lastIndexOf('/')
        return if (lastDot > lastSlash && lastDot < uri.length - 1) {
            uri.substring(lastDot + 1).lowercase()
        } else {
            null
        }
    }

    /**
     * Returns strategy usage statistics (for monitoring/debugging).
     */
    fun getStrategyStats(): Map<String, Int> {
        return strategyStats.mapValues { it.value.get() }
    }
}
