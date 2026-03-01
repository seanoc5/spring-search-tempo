package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import java.time.OffsetDateTime


/**
 * ItemWriter that persists embedding results to ContentChunk entities.
 *
 * Uses native SQL update via ContentChunkRepository.updateEmbedding() to bypass
 * Hibernate type-mapping issues with the vector(1024) column.
 *
 * @param contentChunkRepository Repository for updating content chunks
 */
class EmailEmbeddingWriter(
    private val contentChunkRepository: ContentChunkRepository
) : ItemWriter<ContentChunkDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailEmbeddingWriter::class.java)
    }

    private var totalChunksSaved = 0

    override fun write(chunk: Chunk<out ContentChunkDTO>) {
        var batchChunksSaved = 0

        chunk.items.forEach { dto ->
            val id = dto.id ?: return@forEach
            val embedding = dto.embedding ?: return@forEach
            val generatedAt = dto.embeddingGeneratedAt ?: OffsetDateTime.now()
            val modelName = dto.embeddingModel ?: "unknown"

            val embeddingStr = floatArrayToPgVector(embedding)

            contentChunkRepository.updateEmbedding(id, embeddingStr, generatedAt, modelName)
            batchChunksSaved++
            totalChunksSaved++
            log.debug("Saved embedding for email chunk {} ({} dims)", id, embedding.size)
        }

        if (batchChunksSaved > 0) {
            log.debug("EmailEmbeddingWriter: Saved {} chunks (total: {})", batchChunksSaved, totalChunksSaved)
        }
    }

    /**
     * Convert FloatArray to pgvector string format: [0.1,0.2,...].
     */
    private fun floatArrayToPgVector(embedding: FloatArray): String {
        return embedding.joinToString(",", "[", "]")
    }
}
