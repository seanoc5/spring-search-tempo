package com.oconeco.spring_search_tempo.base.service

import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for hybrid search combining keyword (FTS) and semantic (vector) search.
 *
 * Uses Reciprocal Rank Fusion (RRF) to merge results from both search methods:
 * - FTS provides exact keyword matching with linguistic analysis
 * - Semantic search finds conceptually similar content regardless of keywords
 *
 * RRF formula: score = sum(1 / (k + rank_i)) where k is typically 60
 * This approach is effective because it normalizes rankings across different score scales.
 */
interface HybridSearchService {

    /**
     * Perform hybrid search combining keyword and semantic results.
     *
     * @param query Search query
     * @param limit Maximum results to return
     * @param ftsWeight Weight for FTS results (0.0 to 1.0, default 0.5)
     * @param semanticWeight Weight for semantic results (0.0 to 1.0, default 0.5)
     * @return Combined and re-ranked results
     */
    fun search(
        query: String,
        limit: Int = 20,
        ftsWeight: Double = 0.5,
        semanticWeight: Double = 0.5
    ): List<HybridSearchResult>

    /**
     * Check if hybrid search is available (requires semantic search).
     */
    fun isAvailable(): Boolean

    /**
     * Get hybrid search statistics.
     */
    fun getStats(): HybridSearchStats
}

/**
 * Result from hybrid search with combined ranking.
 */
data class HybridSearchResult(
    val chunkId: Long,
    val text: String,
    val snippet: String?,
    val chunkType: String?,
    val sentiment: String?,
    /** Combined RRF score (higher = more relevant) */
    val hybridScore: Double,
    /** FTS rank (0-1, null if not in FTS results) */
    val ftsRank: Float?,
    /** Semantic similarity (0-1, null if not in semantic results) */
    val semanticSimilarity: Double?,
    /** Source type: FILE, EMAIL, ONEDRIVE */
    val sourceType: String,
    val sourceId: Long?,
    val sourceLabel: String?,
    val sourceUri: String?,
    /** Which search methods found this result */
    val foundBy: Set<SearchMethod>
)

enum class SearchMethod {
    FTS, SEMANTIC, BOTH
}

/**
 * Statistics for hybrid search capabilities.
 */
data class HybridSearchStats(
    val ftsAvailable: Boolean,
    val semanticAvailable: Boolean,
    val hybridAvailable: Boolean,
    val embeddingModel: String?,
    val chunksWithEmbedding: Long,
    val totalChunks: Long
)

@Service
@Transactional(readOnly = true)
class HybridSearchServiceImpl(
    private val fullTextSearchService: FullTextSearchService,
    private val semanticSearchService: SemanticSearchService,
    private val contentChunkRepository: com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
) : HybridSearchService {

    companion object {
        private val log = LoggerFactory.getLogger(HybridSearchServiceImpl::class.java)
        /** RRF constant - standard value that works well across different ranking scales */
        private const val RRF_K = 60
    }

    override fun search(
        query: String,
        limit: Int,
        ftsWeight: Double,
        semanticWeight: Double
    ): List<HybridSearchResult> {
        if (query.isBlank()) {
            return emptyList()
        }

        val effectiveLimit = limit.coerceIn(1, 100)
        // Fetch more results from each source to allow for deduplication and reranking
        val fetchLimit = (effectiveLimit * 2).coerceAtMost(100)

        log.debug("Hybrid search for '{}' (limit={}, ftsWeight={}, semanticWeight={})",
            query, effectiveLimit, ftsWeight, semanticWeight)

        // Normalize weights
        val totalWeight = ftsWeight + semanticWeight
        val normalizedFtsWeight = if (totalWeight > 0) ftsWeight / totalWeight else 0.5
        val normalizedSemanticWeight = if (totalWeight > 0) semanticWeight / totalWeight else 0.5

        // Get FTS results (chunks only for now - they have embeddings)
        val ftsResults = try {
            val pageable = PageRequest.of(0, fetchLimit)
            fullTextSearchService.searchChunks(query, null, pageable).content
        } catch (e: Exception) {
            log.warn("FTS search failed, using semantic only: {}", e.message)
            emptyList()
        }

        // Get semantic results
        val semanticResults = try {
            if (semanticSearchService.isAvailable()) {
                semanticSearchService.search(query, fetchLimit)
            } else {
                log.debug("Semantic search unavailable, using FTS only")
                emptyList()
            }
        } catch (e: Exception) {
            log.warn("Semantic search failed, using FTS only: {}", e.message)
            emptyList()
        }

        // If only one source has results, return those
        if (ftsResults.isEmpty() && semanticResults.isEmpty()) {
            return emptyList()
        }
        if (ftsResults.isEmpty()) {
            return semanticResults.take(effectiveLimit).map { it.toHybridResult(SearchMethod.SEMANTIC) }
        }
        if (semanticResults.isEmpty()) {
            return ftsResults.take(effectiveLimit).map { it.toHybridResult(SearchMethod.FTS) }
        }

        // Merge results using RRF
        val merged = mergeWithRRF(
            ftsResults = ftsResults,
            semanticResults = semanticResults,
            ftsWeight = normalizedFtsWeight,
            semanticWeight = normalizedSemanticWeight
        )

        log.debug("Hybrid search merged {} FTS + {} semantic = {} unique results",
            ftsResults.size, semanticResults.size, merged.size)

        return merged.take(effectiveLimit)
    }

    /**
     * Merge FTS and semantic results using Reciprocal Rank Fusion.
     *
     * RRF score = weight * (1 / (k + rank))
     *
     * This normalizes rankings from different scales and combines them effectively.
     */
    private fun mergeWithRRF(
        ftsResults: List<ChunkSearchResult>,
        semanticResults: List<SemanticSearchResult>,
        ftsWeight: Double,
        semanticWeight: Double
    ): List<HybridSearchResult> {
        // Build maps of chunk ID to rank position and result
        val ftsRanks = ftsResults.mapIndexed { index, result -> result.id to (index + 1) }.toMap()
        val ftsMap = ftsResults.associateBy { it.id }

        val semanticRanks = semanticResults.mapIndexed { index, result -> result.chunkId to (index + 1) }.toMap()
        val semanticMap = semanticResults.associateBy { it.chunkId }

        // Get all unique chunk IDs
        val allChunkIds = (ftsRanks.keys + semanticRanks.keys).distinct()

        // Calculate RRF scores
        val scored = allChunkIds.map { chunkId ->
            val ftsRank = ftsRanks[chunkId]
            val semanticRank = semanticRanks[chunkId]

            val ftsScore = if (ftsRank != null) ftsWeight * (1.0 / (RRF_K + ftsRank)) else 0.0
            val semanticScore = if (semanticRank != null) semanticWeight * (1.0 / (RRF_K + semanticRank)) else 0.0
            val hybridScore = ftsScore + semanticScore

            val foundBy = when {
                ftsRank != null && semanticRank != null -> setOf(SearchMethod.FTS, SearchMethod.SEMANTIC, SearchMethod.BOTH)
                ftsRank != null -> setOf(SearchMethod.FTS)
                else -> setOf(SearchMethod.SEMANTIC)
            }

            // Build result from whichever source we have
            val ftsResult = ftsMap[chunkId]
            val semanticResult = semanticMap[chunkId]

            HybridSearchResult(
                chunkId = chunkId,
                text = semanticResult?.text ?: "",
                snippet = ftsResult?.snippet,
                chunkType = ftsResult?.chunkType ?: semanticResult?.chunkType,
                sentiment = ftsResult?.sentiment ?: semanticResult?.sentiment,
                hybridScore = hybridScore,
                ftsRank = ftsResult?.rank,
                semanticSimilarity = semanticResult?.similarity,
                sourceType = semanticResult?.sourceType ?: "FILE",
                sourceId = semanticResult?.sourceId,
                sourceLabel = ftsResult?.fileLabel ?: semanticResult?.sourceLabel,
                sourceUri = ftsResult?.fileUri ?: semanticResult?.sourceUri,
                foundBy = foundBy
            )
        }

        // Sort by hybrid score descending
        return scored.sortedByDescending { it.hybridScore }
    }

    override fun isAvailable(): Boolean {
        return semanticSearchService.isAvailable()
    }

    override fun getStats(): HybridSearchStats {
        val semanticStats = semanticSearchService.getStats()
        val totalChunks = try {
            contentChunkRepository.count()
        } catch (e: Exception) {
            0L
        }

        return HybridSearchStats(
            ftsAvailable = true, // FTS is always available with PostgreSQL
            semanticAvailable = semanticStats.embeddingServiceAvailable,
            hybridAvailable = semanticStats.embeddingServiceAvailable,
            embeddingModel = semanticStats.modelName,
            chunksWithEmbedding = semanticStats.chunksWithEmbedding,
            totalChunks = totalChunks
        )
    }

    /** Convert ChunkSearchResult to HybridSearchResult */
    private fun ChunkSearchResult.toHybridResult(method: SearchMethod) = HybridSearchResult(
        chunkId = id,
        text = "", // FTS doesn't return full text
        snippet = snippet,
        chunkType = chunkType,
        sentiment = sentiment,
        hybridScore = rank.toDouble(),
        ftsRank = rank,
        semanticSimilarity = null,
        sourceType = "FILE",
        sourceId = null,
        sourceLabel = fileLabel,
        sourceUri = fileUri,
        foundBy = setOf(method)
    )

    /** Convert SemanticSearchResult to HybridSearchResult */
    private fun SemanticSearchResult.toHybridResult(method: SearchMethod) = HybridSearchResult(
        chunkId = chunkId,
        text = text,
        snippet = null,
        chunkType = chunkType,
        sentiment = sentiment,
        hybridScore = similarity,
        ftsRank = null,
        semanticSimilarity = similarity,
        sourceType = sourceType,
        sourceId = sourceId,
        sourceLabel = sourceLabel,
        sourceUri = sourceUri,
        foundBy = setOf(method)
    )
}
