package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.service.ChunkSearchResult
import com.oconeco.spring_search_tempo.base.service.FileSearchResult
import com.oconeco.spring_search_tempo.base.service.FullTextSearchService
import com.oconeco.spring_search_tempo.base.service.SearchResult
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST API for full-text search operations.
 *
 * Provides endpoints for searching indexed content with PostgreSQL FTS.
 * Search includes NLP-enhanced ranking using named entities, nouns, and verbs.
 *
 * Endpoints:
 * - GET /api/search?q={query} - Search all content (files + chunks)
 * - GET /api/search/files?q={query} - Search files only
 * - GET /api/search/chunks?q={query}&sentiment={filter} - Search chunks with optional sentiment filter
 * - GET /api/search/suggest?q={query} - Get search suggestions (future)
 * - GET /api/search/stats - Get search statistics (future)
 *
 * Query syntax:
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
    private val searchService: FullTextSearchService
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
}
