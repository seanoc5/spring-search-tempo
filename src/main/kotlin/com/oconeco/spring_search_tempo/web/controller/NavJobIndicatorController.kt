package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.web.service.NavJobIndicatorService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Site-wide nav indicator for in-flight crawl/sync jobs (issue #73).
 *
 * Two HTMX endpoints:
 * - `GET /nav/jobIndicator` → tiny badge fragment, polled every 5s from
 *   the global layout header. Backed by two COUNT queries (see
 *   [NavJobIndicatorService.loadBadge]).
 * - `GET /nav/jobIndicator/details` → dropdown panel content, fetched on
 *   click. Lists in-flight executions with link-to-detail.
 */
@Controller
@RequestMapping("/nav/jobIndicator")
class NavJobIndicatorController(
    private val navJobIndicatorService: NavJobIndicatorService,
) {

    /**
     * Polled fragment. Returns the badge wrapper element so HTMX can
     * `outerHTML`-swap it cleanly (same root id every poll).
     */
    @GetMapping
    fun badge(model: Model): String {
        model.addAttribute("badge", navJobIndicatorService.loadBadge())
        return "fragments/jobIndicator :: badge"
    }

    /**
     * Dropdown panel content — list of currently running JobRuns with
     * link-to-detail. Cheap (one indexed query), but only hit when the
     * user actually opens the dropdown.
     */
    @GetMapping("/details")
    fun details(model: Model): String {
        model.addAttribute("rows", navJobIndicatorService.loadDetails())
        return "fragments/jobIndicator :: details"
    }
}
