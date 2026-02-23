package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.service.EntityFrequency
import com.oconeco.spring_search_tempo.base.service.EntitySearchResult
import com.oconeco.spring_search_tempo.base.service.EntitySearchService
import com.oconeco.spring_search_tempo.base.service.EntityStats
import com.oconeco.spring_search_tempo.base.service.EntityTypeCount
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST API for named entity search and aggregation.
 *
 * Provides endpoints for querying NLP-extracted named entities:
 * - Search chunks by entity text or type
 * - Get top entities by frequency
 * - Get entity type statistics
 *
 * Entity Types (Stanford CoreNLP NER):
 * - PERSON: People names
 * - ORGANIZATION: Companies, institutions
 * - LOCATION: Places, addresses
 * - DATE: Dates and date expressions
 * - TIME: Time expressions
 * - MONEY: Monetary values
 * - PERCENT: Percentages
 * - And more...
 *
 * Endpoints:
 * - GET /api/entities/search?q={text}&type={type} - Search by entity
 * - GET /api/entities/type/{type} - Get chunks containing entity type
 * - GET /api/entities/top?type={type}&limit={n} - Top entities by frequency
 * - GET /api/entities/types - List entity types with counts
 * - GET /api/entities/stats - Entity extraction statistics
 */
@RestController
@RequestMapping("/api/entities")
class EntityResource(
    private val entitySearchService: EntitySearchService
) {

    /**
     * Search for chunks containing a specific entity.
     *
     * @param q Entity text to search for (required)
     * @param type Optional entity type filter (PERSON, ORGANIZATION, etc.)
     * @param pageable Pagination parameters
     * @return Page of EntitySearchResult
     */
    @GetMapping("/search", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun searchByEntity(
        @RequestParam q: String,
        @RequestParam(required = false) type: String?,
        @PageableDefault(size = 20)
        pageable: Pageable
    ): ResponseEntity<Page<EntitySearchResult>> {
        if (q.isBlank()) {
            return ResponseEntity.badRequest().build()
        }
        val results = entitySearchService.searchByEntity(q.trim(), type?.uppercase(), pageable)
        return ResponseEntity.ok(results)
    }

    /**
     * Get chunks containing entities of a specific type.
     *
     * @param type Entity type (PERSON, ORGANIZATION, LOCATION, DATE, MONEY, etc.)
     * @param pageable Pagination parameters
     * @return Page of EntitySearchResult
     */
    @GetMapping("/type/{type}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun searchByEntityType(
        @PathVariable type: String,
        @PageableDefault(size = 20)
        pageable: Pageable
    ): ResponseEntity<Page<EntitySearchResult>> {
        val results = entitySearchService.searchByEntityType(type.uppercase(), pageable)
        return ResponseEntity.ok(results)
    }

    /**
     * Get the most frequently occurring entities.
     *
     * @param type Optional filter by entity type
     * @param limit Maximum results (default 20, max 100)
     * @return List of EntityFrequency sorted by count descending
     */
    @GetMapping("/top", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getTopEntities(
        @RequestParam(required = false) type: String?,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<List<EntityFrequency>> {
        val safeLimit = limit.coerceIn(1, 100)
        val results = entitySearchService.getTopEntities(type?.uppercase(), safeLimit)
        return ResponseEntity.ok(results)
    }

    /**
     * Get entity type statistics.
     * Shows count of unique entities and total occurrences per type.
     *
     * @return List of EntityTypeCount
     */
    @GetMapping("/types", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getEntityTypes(): ResponseEntity<List<EntityTypeCount>> {
        val results = entitySearchService.getEntityTypeCounts()
        return ResponseEntity.ok(results)
    }

    /**
     * Get overall entity extraction statistics.
     *
     * @return EntityStats with counts and type breakdown
     */
    @GetMapping("/stats", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getEntityStats(): ResponseEntity<EntityStats> {
        val stats = entitySearchService.getEntityStats()
        return ResponseEntity.ok(stats)
    }

    /**
     * Get list of valid entity type names.
     * Useful for building UI dropdowns.
     *
     * @return List of valid entity type strings
     */
    @GetMapping("/type-names", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getValidEntityTypes(): ResponseEntity<Set<String>> {
        return ResponseEntity.ok(EntitySearchService.VALID_ENTITY_TYPES)
    }
}
