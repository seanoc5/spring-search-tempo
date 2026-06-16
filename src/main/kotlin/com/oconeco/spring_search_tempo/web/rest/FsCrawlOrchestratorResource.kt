package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorOutcome
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorRun
import com.oconeco.spring_search_tempo.batch.fscrawl.FsCrawlOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

/**
 * REST API for the FS crawl orchestrator (issue #139).
 *
 *  - `POST /api/crawl/run-all-enabled` — kicks off the sequential sweep
 *    of every enabled `CrawlConfig` on a background thread. Returns
 *    immediately with the tracking id (the `FsCrawlOrchestratorRun.id`).
 *    Operators poll `GET /api/crawl/orchestrator-runs/{id}` for status.
 *
 *  - `GET /api/crawl/orchestrator-runs/active` — returns the currently
 *    in-flight sweep (or 204 No Content). Drives the admin button's
 *    "disable while running" state.
 */
@RestController
@RequestMapping("/api/crawl")
class FsCrawlOrchestratorResource(
    private val fsCrawlOrchestrator: FsCrawlOrchestrator
) {
    companion object {
        private val log = LoggerFactory.getLogger(FsCrawlOrchestratorResource::class.java)
    }

    @PostMapping("/run-all-enabled")
    fun runAllEnabled(
        @AuthenticationPrincipal user: UserDetails?
    ): ResponseEntity<FsOrchestratorSweepResponse> {
        val triggeredBy = user?.username ?: "anonymous"
        log.info("REST API request to run all enabled FS crawls (triggeredBy={})", triggeredBy)

        val handle = fsCrawlOrchestrator.submitAllEnabledCrawls(triggeredBy = triggeredBy)
        val status = if (handle.alreadyRunning) "ALREADY_RUNNING" else "STARTED"
        val message = if (handle.alreadyRunning) {
            "Sweep already in flight (runId=${handle.runId})"
        } else {
            "FS crawl sweep started (runId=${handle.runId})"
        }
        return ResponseEntity.accepted().body(
            FsOrchestratorSweepResponse(
                status = status,
                runId = handle.runId,
                message = message,
                summaryUrl = "/admin/crawl/orchestrator-runs/${handle.runId}"
            )
        )
    }

    @GetMapping("/orchestrator-runs/active")
    fun activeSweep(): ResponseEntity<FsOrchestratorRunResponse> {
        // Cheap polling endpoint — used by the admin button to flip
        // itself between "Run All Enabled" and "Sweep in flight…".
        // Doesn't load outcomes to keep the response small.
        return if (fsCrawlOrchestrator.isSweepInFlight()) {
            ResponseEntity.ok(FsOrchestratorRunResponse.placeholder("RUNNING"))
        } else {
            ResponseEntity.noContent().build()
        }
    }

    @GetMapping("/orchestrator-runs/{id}")
    fun getRun(@PathVariable id: Long): ResponseEntity<FsOrchestratorRunResponse> {
        val run = fsCrawlOrchestrator.findRun(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(FsOrchestratorRunResponse.from(run))
    }
}

/**
 * Response shape for `POST /api/crawl/run-all-enabled`.
 *
 * @property status `STARTED` for a freshly launched sweep, `ALREADY_RUNNING`
 *   when one was already in flight (the runId points at it).
 * @property summaryUrl path operators can follow in a browser to watch
 *   the sweep complete on /admin/crawl/orchestrator-runs/{id}.
 */
data class FsOrchestratorSweepResponse(
    val status: String,
    val runId: Long,
    val message: String,
    val summaryUrl: String
)

/**
 * Response shape for the GET endpoints — flattened so callers don't need
 * to navigate JPA proxies.
 */
data class FsOrchestratorRunResponse(
    val runId: Long?,
    val runStatus: String,
    val triggeredBy: String?,
    val startedAt: OffsetDateTime?,
    val finishedAt: OffsetDateTime?,
    val totalCrawls: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val outcomes: List<FsOrchestratorOutcomeResponse>
) {
    companion object {
        fun placeholder(status: String) = FsOrchestratorRunResponse(
            runId = null,
            runStatus = status,
            triggeredBy = null,
            startedAt = null,
            finishedAt = null,
            totalCrawls = 0,
            succeeded = 0,
            failed = 0,
            skipped = 0,
            outcomes = emptyList()
        )

        fun from(run: FsCrawlOrchestratorRun) = FsOrchestratorRunResponse(
            runId = run.id,
            runStatus = run.runStatus.name,
            triggeredBy = run.triggeredBy,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt,
            totalCrawls = run.totalCrawls,
            succeeded = run.succeeded,
            failed = run.failed,
            skipped = run.skipped,
            outcomes = run.crawlOutcomes.map(FsOrchestratorOutcomeResponse::from)
        )
    }
}

data class FsOrchestratorOutcomeResponse(
    val crawlConfigId: Long?,
    val crawlConfigName: String?,
    val crawlConfigLabel: String?,
    val jobExecutionId: String?,
    val batchStatus: String?,
    val outcome: String,
    val startedAt: OffsetDateTime?,
    val finishedAt: OffsetDateTime?,
    val elapsedMs: Long?,
    val errorMessage: String?
) {
    companion object {
        fun from(o: FsCrawlOrchestratorOutcome) = FsOrchestratorOutcomeResponse(
            crawlConfigId = o.crawlConfigId,
            crawlConfigName = o.crawlConfigName,
            crawlConfigLabel = o.crawlConfigLabel,
            jobExecutionId = o.jobExecutionId,
            batchStatus = o.batchStatus,
            outcome = o.outcome.name,
            startedAt = o.startedAt,
            finishedAt = o.finishedAt,
            elapsedMs = o.elapsedMs,
            errorMessage = o.errorMessage
        )
    }
}
