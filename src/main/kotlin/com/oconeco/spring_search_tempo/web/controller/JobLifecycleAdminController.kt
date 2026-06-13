package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.repos.JobLifecycleEventRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.view.RedirectView
import org.springframework.ui.Model

/**
 * Admin view of recent batch-job lifecycle events: orphan-reaper firings
 * (issue #74) and graceful-shutdown-hook actions (issue #75).
 *
 * The Micrometer counter `tempo.reaper.orphaned_jobs_reaped_total` still
 * covers trend/alerting via `/actuator/prometheus`; this page is the
 * operator's "show me the last N interventions" surface, with a badge
 * column distinguishing reaper events from shutdown events so clustering
 * patterns are obvious.
 *
 * The legacy `/admin/reaper` path 301-redirects here so existing nav
 * bookmarks and the sidebar link still resolve.
 */
@Controller
@RequestMapping("/admin/job-lifecycle")
class JobLifecycleAdminController(
    private val jobLifecycleEventRepository: JobLifecycleEventRepository
) {

    @GetMapping
    fun list(
        @RequestParam(name = "limit", required = false, defaultValue = "50") rawLimit: Int,
        model: Model
    ): String {
        val limit = rawLimit.coerceIn(1, 500)
        val events = jobLifecycleEventRepository.findAllByOrderByEventTimeDesc(PageRequest.of(0, limit))
        model.addAttribute("events", events)
        model.addAttribute("limit", limit)
        return "admin/job-lifecycle/list"
    }
}

@Controller
class JobLifecycleAdminLegacyRedirect {
    @GetMapping("/admin/reaper")
    fun redirect(): RedirectView = RedirectView("/admin/job-lifecycle", true)
}
