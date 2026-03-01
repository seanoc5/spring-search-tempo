package com.oconeco.spring_search_tempo.batch.embedding

import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.service.EmbeddingService
import com.oconeco.spring_search_tempo.base.service.EmbeddingUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.time.OffsetDateTime

/**
 * ItemProcessor that generates vector embeddings for ContentChunks.
 *
 * Calls EmbeddingService.embed() on the chunk's text and sets the embedding,
 * embeddingGeneratedAt, and embeddingModel fields on the DTO.
 *
 * Gracefully handles:
 * - Blank or too-long text (skipped, returns null)
 * - EmbeddingUnavailableException (logs warning once, returns null for all subsequent items)
 *
 * @param embeddingService Service for generating embeddings
 * @param maxTextLength Maximum text length to embed (default 8192)
 */
class EmbeddingChunkProcessor(
    private val embeddingService: EmbeddingService,
    private val maxTextLength: Int = 8192
) : ItemProcessor<ContentChunkDTO, ContentChunkDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(EmbeddingChunkProcessor::class.java)
    }

    @Volatile
    private var serviceUnavailable = false

    override fun process(item: ContentChunkDTO): ContentChunkDTO? {
        // If service went down during this step, skip remaining items
        if (serviceUnavailable) {
            return null
        }

        val text = item.text
        if (text.isNullOrBlank()) {
            log.debug("Skipping chunk {} - no text", item.id)
            return null
        }

        if (text.length > maxTextLength) {
            log.debug("Skipping chunk {} - text too long ({} chars, max {})",
                item.id, text.length, maxTextLength)
            return null
        }

        return try {
            val result = embeddingService.embed(text)

            item.embedding = result.embedding
            item.embeddingGeneratedAt = OffsetDateTime.now()
            item.embeddingModel = result.modelName

            log.debug("Generated embedding for chunk {} ({} dims, {} chars)",
                item.id, result.dimensions, text.length)

            item
        } catch (e: EmbeddingUnavailableException) {
            log.warn("Embedding service unavailable, skipping remaining chunks: {}", e.message)
            serviceUnavailable = true
            null
        } catch (e: Exception) {
            log.error("Error generating embedding for chunk {}: {}", item.id, e.message, e)
            null
        }
    }
}
