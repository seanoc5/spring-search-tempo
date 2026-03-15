package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.service.ChunkSearchResult
import com.oconeco.spring_search_tempo.base.service.FileSearchResult
import com.oconeco.spring_search_tempo.base.service.FullTextSearchService
import com.oconeco.spring_search_tempo.base.service.SearchResult
import com.oconeco.spring_search_tempo.base.service.SemanticSearchResult
import com.oconeco.spring_search_tempo.base.service.SemanticSearchService
import com.oconeco.spring_search_tempo.base.service.SemanticSearchStats
import com.oconeco.spring_search_tempo.base.service.HybridSearchResult
import com.oconeco.spring_search_tempo.base.service.HybridSearchService
import com.oconeco.spring_search_tempo.base.service.HybridSearchStats
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST API for full-text and semantic search operations.
 *
 * Provides endpoints for searching indexed content using:
 * - PostgreSQL FTS (keyword-based with NLP-enhanced ranking)
 * - pgvector semantic search (embedding-based similarity)
 *
 * Full-Text Search Endpoints:
 * - GET /api/search?q={query} - Search all content (files + chunks)
 * - GET /api/search/files?q={query} - Search files only
 * - GET /api/search/chunks?q={query}&sentiment={filter} - Search chunks with sentiment filter
 * - GET /api/search/suggest?q={query} - Get search suggestions (future)
 * - GET /api/search/stats - Get search statistics (future)
 *
 * Semantic Search Endpoints:
 * - GET /api/search/semantic?q={query} - Semantic similarity search
 * - GET /api/search/semantic/similar/{chunkId} - Find similar content to a chunk
 * - GET /api/search/semantic/stats - Embedding service status
 *
 * Query syntax (FTS):
 * - Simple words: "spring kotlin" (automatically joined with AND)
 * - AND operator: "spring & kotlin"
 * - OR operator: "spring | kotlin"
 * - NOT operator: "spring & !java"
 * - Phrase search: "\"exact phrase\""
 *
 * Sentiment filter values: POSITIVE, NEGATIVE, NEUTRAL (case-insensitive)
 */
@RestController
@RequestMapping("/api/search")
class SearchResource(
    private val searchService: FullTextSearchService,
    private val semanticSearchService: SemanticSearchService,
    private val hybridSearchService: HybridSearchService
) {

    /**
     * Search across all indexed files and content chunks.
     * Results are ranked by relevance using PostgreSQL's ts_rank.
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun searchAll(
        @RequestParam q: String,
        @PageableDefault(size = 20, sort = ["rank"], direction = Sort.Direction.DESC)
        pageable: Pageable
    ): ResponseEntity<Page<SearchResult>> {
        val results = searchService.searchAll(q, pageable)
        return ResponseEntity.ok(results)
    }

    /**
     * Search only in the fs_file table.
     * Returns file-specific metadata including author, title, and content type.
     */
    @GetMapping("/files", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun searchFiles(
        @RequestParam q: String,
        @PageableDefault(size = 20, sort = ["rank"], direction = Sort.Direction.DESC)
        pageable: Pageable
    ): ResponseEntity<Page<FileSearchResult>> {
        val results = searchService.searchFiles(q, pageable)
        return ResponseEntity.ok(results)
    }

    /**
     * Search only in the content_chunks table.
     * Returns chunk-specific information including chunk number, type, and NLP data.
     *
     * NLP-enhanced features:
     * - Results ranked by named entities (highest), nouns, text, and verbs (lowest)
     * - Returns sentiment analysis (POSITIVE/NEGATIVE/NEUTRAL) and score
     * - Returns named entities extracted from the chunk
     * - Optional sentiment filter to narrow results
     *
     * @param q Search query
     * @param sentiment Optional filter: POSITIVE, NEGATIVE, NEUTRAL (case-insensitive)
     * @param pageable Pagination parameters
     */
    @GetMapping("/chunks", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun searchChunks(
        @RequestParam q: String,
        @RequestParam(required = false) sentiment: String?,
        @PageableDefault(size = 20, sort = ["rank"], direction = Sort.Direction.DESC)
        pageable: Pageable
    ): ResponseEntity<Page<ChunkSearchResult>> {
        val results = searchService.searchChunks(q, sentiment, pageable)
        return ResponseEntity.ok(results)
    }

    /**
     * Get search term suggestions.
     * Future implementation - currently returns empty list.
     */
    @GetMapping("/suggest", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getSuggestions(
        @RequestParam q: String,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<List<String>> {
        // TODO: Implement search suggestions using PostgreSQL pg_trgm or similar
        return ResponseEntity.ok(emptyList())
    }

    /**
     * Get search statistics.
     * Future implementation - currently returns placeholder.
     */
    @GetMapping("/stats", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStats(): ResponseEntity<Map<String, Any>> {
        // TODO: Query the search_stats materialized view
        return ResponseEntity.ok(mapOf(
            "message" to "Search statistics not yet implemented",
            "todo" to "Query search_stats materialized view"
        ))
    }

    // ==================== SEMANTIC SEARCH (Vector Similarity) ====================

    /**
     * Semantic search using vector embeddings.
     *
     * Finds content chunks that are semantically similar to the query text,
     * regardless of exact keyword matches. Uses cosine similarity with pgvector.
     *
     * @param q The query text to find similar content for
     * @param limit Maximum number of results (default 10, max 100)
     * @param maxDistance Optional maximum cosine distance threshold (0-2, lower = more similar)
     * @return List of semantically similar chunks ordered by similarity
     */
    @GetMapping("/semantic", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun searchSemantic(
        @RequestParam q: String,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(required = false) maxDistance: Double?
    ): ResponseEntity<List<SemanticSearchResult>> {
        val effectiveLimit = limit.coerceIn(1, 100)
        val results = semanticSearchService.search(q, effectiveLimit, maxDistance)
        return ResponseEntity.ok(results)
    }

    /**
     * Find content similar to a specific chunk.
     *
     * Uses the embedding of an existing chunk to find other semantically
     * similar content. Useful for "related content" features.
     *
     * @param chunkId The ID of the chunk to find similar content for
     * @param limit Maximum number of results (default 10, max 100)
     * @return List of similar chunks, excluding the source chunk
     */
    @GetMapping("/semantic/similar/{chunkId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun findSimilar(
        @PathVariable chunkId: Long,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<List<SemanticSearchResult>> {
        val effectiveLimit = limit.coerceIn(1, 100)
        val results = semanticSearchService.findSimilar(chunkId, effectiveLimit)
        return ResponseEntity.ok(results)
    }

    /**
     * Get semantic search status and statistics.
     *
     * Returns information about the embedding service availability,
     * model in use, and embedding coverage of content chunks.
     */
    @GetMapping("/semantic/stats", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getSemanticStats(): ResponseEntity<SemanticSearchStats> {
        val stats = semanticSearchService.getStats()
        return ResponseEntity.ok(stats)
    }

    // ==================== HYBRID SEARCH (FTS + Semantic) ====================

    /**
     * Hybrid search combining keyword (FTS) and semantic (vector) results.
     *
     * Uses Reciprocal Rank Fusion (RRF) to merge results from both search methods:
     * - FTS provides exact keyword matching with linguistic analysis
     * - Semantic search finds conceptually similar content regardless of keywords
     *
     * @param q Search query
     * @param limit Maximum results (default 20, max 100)
     * @param ftsWeight Weight for FTS results (0.0-1.0, default 0.5)
     * @param semanticWeight Weight for semantic results (0.0-1.0, default 0.5)
     * @return Combined and re-ranked results
     */
    @GetMapping("/hybrid", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun searchHybrid(
        @RequestParam q: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0.5") ftsWeight: Double,
        @RequestParam(defaultValue = "0.5") semanticWeight: Double
    ): ResponseEntity<List<HybridSearchResult>> {
        val effectiveLimit = limit.coerceIn(1, 100)
        val results = hybridSearchService.search(q, effectiveLimit, ftsWeight, semanticWeight)
        return ResponseEntity.ok(results)
    }

    /**
     * Get hybrid search status and statistics.
     */
    @GetMapping("/hybrid/stats", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getHybridStats(): ResponseEntity<HybridSearchStats> {
        val stats = hybridSearchService.getStats()
        return ResponseEntity.ok(stats)
    }
}
