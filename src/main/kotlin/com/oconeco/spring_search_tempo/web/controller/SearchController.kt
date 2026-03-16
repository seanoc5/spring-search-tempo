package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.domain.EmailCategory
import com.oconeco.spring_search_tempo.base.model.SearchFilterDTO
import com.oconeco.spring_search_tempo.base.service.ContentType
import com.oconeco.spring_search_tempo.base.service.EntitySearchService
import com.oconeco.spring_search_tempo.base.service.FullTextSearchService
import com.oconeco.spring_search_tempo.base.service.SemanticSearchService
import com.oconeco.spring_search_tempo.base.service.HybridSearchService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate

/**
 * Web controller for search interface.
 */
@Controller
@RequestMapping("/search")
class SearchController(
    private val searchService: FullTextSearchService,
    private val semanticSearchService: SemanticSearchService,
    private val hybridSearchService: HybridSearchService
) {

    // Common entity types for UI filtering
    companion object {
        val COMMON_ENTITY_TYPES = listOf("PERSON", "ORGANIZATION", "LOCATION", "DATE", "MONEY")
    }

    @GetMapping
    fun search(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) types: List<String>?,
        @RequestParam(required = false) sentiment: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fromDate: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) toDate: LocalDate?,
        @RequestParam(required = false) author: String?,
        @RequestParam(required = false) entityTypes: List<String>?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        model: Model
    ): String {
        model.addAttribute("query", q ?: "")

        // Parse content types (default to all if none specified)
        val contentTypes = if (types.isNullOrEmpty()) {
            ContentType.entries.toSet()
        } else {
            types.mapNotNull { type ->
                try {
                    ContentType.valueOf(type.uppercase())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }.toSet().ifEmpty { ContentType.entries.toSet() }
        }
        model.addAttribute("selectedTypes", contentTypes.map { it.name }.toSet())
        model.addAttribute("allTypes", ContentType.entries.map { it.name })

        // Parse email category filter
        val emailCategory = category?.let {
            try {
                EmailCategory.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        model.addAttribute("selectedCategory", emailCategory?.name ?: "")
        model.addAttribute("allCategories", EmailCategory.entries.map { it.name })

        // Parse entity types filter
        val validEntityTypes = entityTypes
            ?.filter { it.uppercase() in EntitySearchService.VALID_ENTITY_TYPES }
            ?.map { it.uppercase() }
            ?.toSet()
        model.addAttribute("selectedEntityTypes", validEntityTypes ?: emptySet<String>())
        model.addAttribute("allEntityTypes", COMMON_ENTITY_TYPES)

        // Pass other filter values to the view
        model.addAttribute("selectedSentiment", sentiment ?: "")
        model.addAttribute("selectedFromDate", fromDate?.toString() ?: "")
        model.addAttribute("selectedToDate", toDate?.toString() ?: "")
        model.addAttribute("selectedAuthor", author ?: "")

        if (!q.isNullOrBlank()) {
            try {
                val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "rank"))
                val filter = SearchFilterDTO(
                    query = q,
                    contentTypes = contentTypes,
                    sentiment = sentiment,
                    emailCategory = emailCategory,
                    fromDate = fromDate,
                    toDate = toDate,
                    author = author,
                    entityTypes = validEntityTypes
                )
                val results = searchService.searchWithFilters(filter, pageable)

                model.addAttribute("results", results)
                model.addAttribute("totalResults", results.totalElements)
                model.addAttribute("hasResults", results.hasContent())
            } catch (e: Exception) {
                model.addAttribute("error", "Search failed: ${e.message}")
                model.addAttribute("hasResults", false)
            }
        } else {
            model.addAttribute("hasResults", false)
        }

        return "search/results"
    }

    @GetMapping("/files")
    fun searchFiles(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        model: Model
    ): String {
        model.addAttribute("query", q ?: "")
        model.addAttribute("searchType", "files")

        if (!q.isNullOrBlank()) {
            try {
                val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "rank"))
                val results = searchService.searchFiles(q, pageable)

                model.addAttribute("results", results)
                model.addAttribute("totalResults", results.totalElements)
                model.addAttribute("hasResults", results.hasContent())
            } catch (e: Exception) {
                model.addAttribute("error", "Search failed: ${e.message}")
                model.addAttribute("hasResults", false)
            }
        } else {
            model.addAttribute("hasResults", false)
        }

        return "search/file-results"
    }

    @GetMapping("/chunks")
    fun searchChunks(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) sentiment: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        model: Model
    ): String {
        model.addAttribute("query", q ?: "")
        model.addAttribute("sentiment", sentiment ?: "")
        model.addAttribute("searchType", "chunks")

        if (!q.isNullOrBlank()) {
            try {
                val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "rank"))
                val results = searchService.searchChunks(q, sentiment, pageable)

                model.addAttribute("results", results)
                model.addAttribute("totalResults", results.totalElements)
                model.addAttribute("hasResults", results.hasContent())
            } catch (e: Exception) {
                model.addAttribute("error", "Search failed: ${e.message}")
                model.addAttribute("hasResults", false)
            }
        } else {
            model.addAttribute("hasResults", false)
        }

        return "search/chunk-results"
    }

    @GetMapping("/semantic")
    fun searchSemantic(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        model: Model
    ): String {
        model.addAttribute("query", q ?: "")
        model.addAttribute("searchType", "semantic")

        // Add stats for display
        val stats = semanticSearchService.getStats()
        model.addAttribute("stats", stats)

        if (!q.isNullOrBlank()) {
            try {
                val effectiveLimit = limit.coerceIn(1, 100)
                val results = semanticSearchService.search(q, effectiveLimit)

                model.addAttribute("results", results)
                model.addAttribute("totalResults", results.size)
                model.addAttribute("hasResults", results.isNotEmpty())
            } catch (e: Exception) {
                model.addAttribute("error", "Semantic search failed: ${e.message}")
                model.addAttribute("hasResults", false)
            }
        } else {
            model.addAttribute("hasResults", false)
        }

        return "search/semantic-results"
    }

    @GetMapping("/hybrid")
    fun searchHybrid(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0.5") ftsWeight: Double,
        @RequestParam(defaultValue = "0.5") semanticWeight: Double,
        model: Model
    ): String {
        model.addAttribute("query", q ?: "")
        model.addAttribute("ftsWeight", ftsWeight)
        model.addAttribute("semanticWeight", semanticWeight)
        model.addAttribute("searchType", "hybrid")

        // Add stats for display
        val stats = hybridSearchService.getStats()
        model.addAttribute("stats", stats)

        if (!q.isNullOrBlank()) {
            try {
                val effectiveLimit = limit.coerceIn(1, 100)
                val results = hybridSearchService.search(q, effectiveLimit, ftsWeight, semanticWeight)

                model.addAttribute("results", results)
                model.addAttribute("totalResults", results.size)
                model.addAttribute("hasResults", results.isNotEmpty())
            } catch (e: Exception) {
                model.addAttribute("error", "Hybrid search failed: ${e.message}")
                model.addAttribute("hasResults", false)
            }
        } else {
            model.addAttribute("hasResults", false)
        }

        return "search/hybrid-results"
    }
}
