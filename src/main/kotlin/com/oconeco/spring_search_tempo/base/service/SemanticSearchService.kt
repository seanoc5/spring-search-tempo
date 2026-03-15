package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for semantic (vector similarity) search using pgvector embeddings.
 *
 * Provides K-NN search capabilities over content chunks that have been embedded.
 * Uses cosine distance for similarity matching via the HNSW index.
 */
interface SemanticSearchService {

    /**
     * Search for semantically similar content to the given query text.
     *
     * @param queryText The text to search for
     * @param limit Maximum number of results (default 10)
     * @param maxDistance Optional maximum cosine distance threshold (0-2, lower = more similar)
     * @return List of search results ordered by similarity
     */
    fun search(queryText: String, limit: Int = 10, maxDistance: Double? = null): List<SemanticSearchResult>

    /**
     * Find content similar to a specific chunk.
     *
     * @param chunkId The ID of the chunk to find similar content for
     * @param limit Maximum number of results (default 10)
     * @return List of similar chunks, excluding the source chunk
     */
    fun findSimilar(chunkId: Long, limit: Int = 10): List<SemanticSearchResult>

    /**
     * Check if semantic search is available (embedding service running).
     */
    fun isAvailable(): Boolean

    /**
     * Get statistics about embedded content.
     */
    fun getStats(): SemanticSearchStats
}

/**
 * Result of a semantic search query.
 */
data class SemanticSearchResult(
    val chunkId: Long,
    val text: String,
    val chunkType: String?,
    val sentiment: String?,
    val sentimentScore: Double?,
    /** Cosine distance (0 = identical, 2 = opposite) */
    val distance: Double,
    /** Similarity score (1 = identical, 0 = orthogonal, -1 = opposite) */
    val similarity: Double,
    /** Source type: FILE, EMAIL, ONEDRIVE */
    val sourceType: String,
    /** ID of the source entity (FSFile, EmailMessage, or OneDriveItem) */
    val sourceId: Long?,
    /** Label/title of the source (populated by service) */
    val sourceLabel: String? = null,
    /** URI of the source (populated by service) */
    val sourceUri: String? = null
)

/**
 * Statistics about semantic search capabilities.
 */
data class SemanticSearchStats(
    val embeddingServiceAvailable: Boolean,
    val modelName: String?,
    val chunksWithEmbedding: Long,
    val chunksPendingEmbedding: Long,
    val gpuMode: String?
)

@Service
@Transactional(readOnly = true)
class SemanticSearchServiceImpl(
    private val embeddingService: EmbeddingService,
    private val contentChunkRepository: ContentChunkRepository,
    private val fsFileRepository: FSFileRepository,
    private val emailMessageRepository: EmailMessageRepository
) : SemanticSearchService {

    companion object {
        private val log = LoggerFactory.getLogger(SemanticSearchServiceImpl::class.java)
    }

    override fun search(queryText: String, limit: Int, maxDistance: Double?): List<SemanticSearchResult> {
        if (queryText.isBlank()) {
            return emptyList()
        }

        // Generate embedding for query text
        val queryEmbedding = try {
            embeddingService.embed(queryText)
        } catch (e: EmbeddingUnavailableException) {
            log.warn("Embedding service unavailable for semantic search: {}", e.message)
            return emptyList()
        }

        val embeddingString = floatArrayToPgVector(queryEmbedding.embedding)
        log.debug("Searching for {} similar chunks (maxDistance={})", limit, maxDistance ?: "none")

        val results = if (maxDistance != null) {
            contentChunkRepository.findNearestByEmbeddingWithThreshold(embeddingString, maxDistance, limit)
        } else {
            contentChunkRepository.findNearestByEmbedding(embeddingString, limit)
        }

        return mapResults(results)
    }

    override fun findSimilar(chunkId: Long, limit: Int): List<SemanticSearchResult> {
        log.debug("Finding {} similar chunks to chunk {}", limit, chunkId)

        val results = contentChunkRepository.findSimilarToChunk(chunkId, limit)
        return mapResults(results)
    }

    override fun isAvailable(): Boolean {
        return try {
            embeddingService.isAvailable()
        } catch (e: Exception) {
            log.debug("Embedding service check failed: {}", e.message)
            false
        }
    }

    override fun getStats(): SemanticSearchStats {
        val available = isAvailable()
        val gpuStatus = if (available) {
            try {
                embeddingService.checkGpuStatus()
            } catch (e: Exception) {
                null
            }
        } else null

        return SemanticSearchStats(
            embeddingServiceAvailable = available,
            modelName = if (available) embeddingService.getModelName() else null,
            chunksWithEmbedding = contentChunkRepository.countByEmbeddingGeneratedAtIsNotNull(),
            chunksPendingEmbedding = contentChunkRepository.countEmbeddingPending(),
            gpuMode = gpuStatus?.mode
        )
    }

    /**
     * Map raw SQL results to SemanticSearchResult objects.
     * Result columns: [id, text, chunkType, sentiment, sentimentScore, distance, conceptId, emailMessageId, oneDriveItemId]
     */
    private fun mapResults(rawResults: List<Array<Any?>>): List<SemanticSearchResult> {
        // Pre-fetch source labels for all results
        val fileIds = rawResults.mapNotNull { it[6] as? Number }.map { it.toLong() }.distinct()
        val emailIds = rawResults.mapNotNull { it[7] as? Number }.map { it.toLong() }.distinct()

        val fileLabels = if (fileIds.isNotEmpty()) {
            fsFileRepository.findAllById(fileIds).associate { it.id!! to (it.label to it.uri) }
        } else emptyMap()

        val emailLabels = if (emailIds.isNotEmpty()) {
            emailMessageRepository.findAllById(emailIds).associate { it.id!! to (it.subject to it.messageId) }
        } else emptyMap()

        return rawResults.map { row ->
            val id = (row[0] as Number).toLong()
            val text = row[1] as? String ?: ""
            val chunkType = row[2] as? String
            val sentiment = row[3] as? String
            val sentimentScore = (row[4] as? Number)?.toDouble()
            val distance = (row[5] as Number).toDouble()
            val conceptId = (row[6] as? Number)?.toLong()
            val emailMessageId = (row[7] as? Number)?.toLong()
            val oneDriveItemId = (row[8] as? Number)?.toLong()

            // Determine source type and info
            val (sourceType, sourceId, sourceLabel, sourceUri) = when {
                conceptId != null -> {
                    val (label, uri) = fileLabels[conceptId] ?: (null to null)
                    listOf("FILE", conceptId, label, uri)
                }
                emailMessageId != null -> {
                    val (subject, messageId) = emailLabels[emailMessageId] ?: (null to null)
                    listOf("EMAIL", emailMessageId, subject, messageId)
                }
                oneDriveItemId != null -> listOf("ONEDRIVE", oneDriveItemId, null, null)
                else -> listOf("UNKNOWN", null, null, null)
            }

            SemanticSearchResult(
                chunkId = id,
                text = text,
                chunkType = chunkType,
                sentiment = sentiment,
                sentimentScore = sentimentScore,
                distance = distance,
                similarity = 1.0 - (distance / 2.0), // Convert cosine distance to similarity
                sourceType = sourceType as String,
                sourceId = sourceId as? Long,
                sourceLabel = sourceLabel as? String,
                sourceUri = sourceUri as? String
            )
        }
    }

    /**
     * Convert FloatArray to pgvector string format: "[0.1,0.2,...]"
     */
    private fun floatArrayToPgVector(embedding: FloatArray): String {
        return embedding.joinToString(",", "[", "]")
    }
}
