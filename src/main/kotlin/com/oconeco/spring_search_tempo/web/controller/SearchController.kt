package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.domain.EmailCategory
import com.oconeco.spring_search_tempo.base.model.SearchFilterDTO
import com.oconeco.spring_search_tempo.base.service.ContentType
import com.oconeco.spring_search_tempo.base.service.FullTextSearchService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Web controller for search interface.
 */
@Controller
@RequestMapping("/search")
class SearchController(
    private val searchService: FullTextSearchService
) {

    @GetMapping
    fun search(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) types: List<String>?,
        @RequestParam(required = false) sentiment: String?,
        @RequestParam(required = false) category: String?,
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

        // Pass sentiment filter
        model.addAttribute("selectedSentiment", sentiment ?: "")

        if (!q.isNullOrBlank()) {
            try {
                val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "rank"))
                val filter = SearchFilterDTO(
                    query = q,
                    contentTypes = contentTypes,
                    sentiment = sentiment,
                    emailCategory = emailCategory
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
}
