package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.service.EntitySearchService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Web view of [EntitySearchService] — the entity-detail / entity-search route.
 *
 * The FSFile NLP section links each named-entity span here with the entity's
 * text *and type*, so e.g. "Apple" (ORGANIZATION) and "Seattle" (LOCATION) land
 * on type-aware result pages instead of falling through to plain FTS.
 */
@Controller
@RequestMapping("/entity")
class EntityController(
    private val entitySearchService: EntitySearchService
) {

    @GetMapping
    fun detail(
        @RequestParam(name = "text", required = false) text: String?,
        @RequestParam(name = "type", required = false) type: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        model: Model
    ): String {
        val trimmedText = text?.trim().orEmpty()
        val normalizedType = type?.uppercase()?.takeIf { it.isNotBlank() }
        val effectiveType = normalizedType?.takeIf { it in EntitySearchService.VALID_ENTITY_TYPES }

        model.addAttribute("entityText", trimmedText)
        model.addAttribute("entityType", effectiveType)
        model.addAttribute("requestedType", normalizedType)
        model.addAttribute("typeIgnored", normalizedType != null && effectiveType == null)
        model.addAttribute("validTypes", EntitySearchService.VALID_ENTITY_TYPES.toSortedSet())

        if (trimmedText.isBlank()) {
            model.addAttribute("hasResults", false)
            model.addAttribute("totalResults", 0L)
            return "entity/detail"
        }

        try {
            val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100))
            val results = entitySearchService.searchByEntity(trimmedText, effectiveType, pageable)
            model.addAttribute("results", results)
            model.addAttribute("totalResults", results.totalElements)
            model.addAttribute("hasResults", results.hasContent())
        } catch (e: Exception) {
            model.addAttribute("error", "Entity search failed: ${e.message}")
            model.addAttribute("hasResults", false)
            model.addAttribute("totalResults", 0L)
        }

        return "entity/detail"
    }
}
