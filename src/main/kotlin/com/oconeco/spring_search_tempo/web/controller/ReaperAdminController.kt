package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.repos.ReapedJobRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Admin view of the most-recent orphan-reaping events (issue #74).
 *
 * The Micrometer counter `tempo.reaper.orphaned_jobs_reaped_total` covers
 * trend/alerting via `/actuator/prometheus`; this page is the operator's
 * "show me the last N reapings" surface so clustering patterns (e.g., a
 * specific account or job name suddenly reaping repeatedly) are obvious
 * without scraping Prometheus.
 */
@Controller
@RequestMapping("/admin/reaper")
class ReaperAdminController(
    private val reapedJobRepository: ReapedJobRepository
) {

    @GetMapping
    fun list(
        @RequestParam(name = "limit", required = false, defaultValue = "50") rawLimit: Int,
        model: Model
    ): String {
        val limit = rawLimit.coerceIn(1, 500)
        val reapings = reapedJobRepository.findAllByOrderByReapedAtDesc(PageRequest.of(0, limit))
        model.addAttribute("reapings", reapings)
        model.addAttribute("limit", limit)
        return "admin/reaper/list"
    }
}
