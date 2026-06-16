package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorRunStatus
import com.oconeco.spring_search_tempo.base.repos.FsCrawlOrchestratorOutcomeRepository
import com.oconeco.spring_search_tempo.base.repos.FsCrawlOrchestratorRunRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Admin view of past FS crawl orchestrator sweeps (issue #139).
 *
 * Two pages:
 *  - List: recent [FsCrawlOrchestratorRun] rows with totals + elapsed time.
 *  - Detail: per-run drilldown listing every [FsCrawlOrchestratorOutcome]
 *    with its batch status, elapsed time, and error message (if any).
 *
 * Diagnostic surface — not a primary observability dashboard. Operators
 * who want trend/alerting should still rely on Micrometer counters and
 * the existing /admin/job-lifecycle view.
 */
@Controller
@RequestMapping("/admin/crawl/orchestrator-runs")
class FsCrawlOrchestratorAdminController(
    private val runRepository: FsCrawlOrchestratorRunRepository,
    private val outcomeRepository: FsCrawlOrchestratorOutcomeRepository
) {

    @GetMapping
    fun list(
        @RequestParam(name = "limit", required = false, defaultValue = "50") rawLimit: Int,
        model: Model
    ): String {
        val limit = rawLimit.coerceIn(1, 500)
        val runs = runRepository.findAllByStartedAtDesc(PageRequest.of(0, limit))
        model.addAttribute("runs", runs.content)
        model.addAttribute("limit", limit)
        model.addAttribute("anyInFlight",
            runRepository.countByRunStatus(FsCrawlOrchestratorRunStatus.RUNNING) > 0L)
        return "admin/orchestrator-runs/list"
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long, model: Model): String {
        val run = runRepository.findByIdWithOutcomes(id)
            ?: return "redirect:/admin/crawl/orchestrator-runs"
        // Fall back to a fresh repo query in case the @OneToMany ordering
        // didn't materialize in the cached collection (Hibernate sometimes
        // skips @OrderBy on first load after persist within the same TX).
        val outcomes = if (run.crawlOutcomes.isEmpty()) {
            outcomeRepository.findByOrchestratorRunIdOrderByStartedAtAsc(id)
        } else {
            run.crawlOutcomes.sortedWith(
                compareBy({ it.startedAt }, { it.id })
            )
        }
        model.addAttribute("run", run)
        model.addAttribute("outcomes", outcomes)
        return "admin/orchestrator-runs/detail"
    }
}
