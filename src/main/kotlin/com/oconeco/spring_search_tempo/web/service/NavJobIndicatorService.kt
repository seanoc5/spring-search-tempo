package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * View-model service powering the site-wide "in-flight jobs" nav indicator
 * (issue #73). Backed entirely by the [JobRunRepository] table so the
 * 5-second poll endpoint runs as cheap SQL aggregates — no JobExplorer
 * scan, no JobInstance/JobExecution walks.
 *
 * Two read shapes:
 * - [loadBadge]: single COUNT-style call pair, polled every 5s by every page.
 * - [loadDetails]: small ROW fetch, called on demand when the user opens
 *   the dropdown panel.
 */
@Service
class NavJobIndicatorService(
    private val jobRunRepository: JobRunRepository,
) {
    companion object {
        /** Window for treating a FAILED JobRun as "recent" enough to warrant a red dot. */
        const val RECENT_FAILURE_WINDOW_MINUTES = 5L

        /**
         * Heartbeat-freshness threshold for "actually running" filtering.
         *
         * A JobRun whose process has died will stop heartbeating; after this
         * window we treat it as a ghost row and exclude it from the badge.
         * The orphan reaper (issue #64) eventually sweeps these via the
         * BatchJobExecution side, but the JobRun row can lag — without this
         * filter the badge would spin forever and the dropdown would list
         * jobs that no JVM is actually working on.
         *
         * Matches `BatchAdminService.HEARTBEAT_STALE_MINUTES` so the badge
         * agrees with the batch dashboard's stale detection.
         */
        const val HEARTBEAT_FRESH_MINUTES = 2L
    }

    /**
     * Cheap aggregate read for the polled badge. Two COUNT queries hitting
     * indexes on `run_status` / `finish_time` / `last_heartbeat_at`.
     */
    @Transactional(readOnly = true)
    fun loadBadge(): NavJobIndicatorBadge {
        val now = OffsetDateTime.now()
        val running = jobRunRepository.countFreshRunningJobs(now.minusMinutes(HEARTBEAT_FRESH_MINUTES))
        val recentFailures = jobRunRepository.countByRunStatusAndFinishTimeGreaterThanEqual(
            RunStatus.FAILED,
            now.minusMinutes(RECENT_FAILURE_WINDOW_MINUTES),
        )
        return NavJobIndicatorBadge(
            runningCount = running,
            recentFailureCount = recentFailures,
        )
    }

    /**
     * On-demand list of currently running JobRuns for the click-opened
     * dropdown panel. Small set (one row per in-flight execution), so it's
     * fine to materialise the entities and project to a view DTO here.
     *
     * Filtered by the same heartbeat-fresh predicate as [loadBadge] so the
     * count and the listed rows always agree.
     */
    @Transactional(readOnly = true)
    fun loadDetails(): List<NavJobIndicatorRow> {
        val threshold = OffsetDateTime.now().minusMinutes(HEARTBEAT_FRESH_MINUTES)
        val runs = jobRunRepository.findFreshRunningJobs(threshold)
        return runs
            .sortedByDescending { it.startTime ?: OffsetDateTime.MIN }
            .map { run ->
                NavJobIndicatorRow(
                    jobRunId = run.id,
                    jobName = run.jobName ?: "(unnamed)",
                    source = run.currentStepName,
                    startedAt = run.startTime,
                    status = run.runStatus.name,
                    detailLink = detailLinkFor(run.jobName, run.springBatchJobExecutionId),
                )
            }
    }

    /**
     * Derive a clickable detail-page URL from the JobRun's `jobName` (account-
     * scoped jobs encode the entity id) with a generic fallback to the
     * Batch Admin execution page.
     *
     * Patterns matched (see BatchAdminService.normalizeJobName for the
     * canonical list of in-tree job names):
     * - `emailQuickSync_<id>` / `emailQuickSyncJob-<id>` → /emailAccounts/{id}
     * - `oneDriveSync_<id>` / `oneDriveSyncJob_<id>` → /oneDriveAccounts/{id}
     * - anything else with a Spring Batch execution id → /batch/{executionId}
     * - otherwise → /batch (the executions list)
     */
    private fun detailLinkFor(jobName: String?, executionId: String?): String {
        if (jobName != null) {
            extractTrailingId(jobName, "emailQuickSync_")?.let { return "/emailAccounts/$it" }
            extractTrailingId(jobName, "emailQuickSyncJob-")?.let { return "/emailAccounts/$it" }
            extractTrailingId(jobName, "oneDriveSync_")?.let { return "/oneDriveAccounts/$it" }
            extractTrailingId(jobName, "oneDriveSyncJob_")?.let { return "/oneDriveAccounts/$it" }
        }
        val execId = executionId?.toLongOrNull()
        return if (execId != null) "/batch/$execId" else "/batch"
    }

    private fun extractTrailingId(jobName: String, prefix: String): Long? {
        if (!jobName.startsWith(prefix)) return null
        // The suffix may include a launch counter ("_2051023") so take the
        // first numeric segment after the prefix.
        val tail = jobName.removePrefix(prefix)
        val firstSegment = tail.takeWhile { it.isDigit() }
        return firstSegment.toLongOrNull()
    }
}

/**
 * Tiny badge model: count of in-flight jobs plus a flag for "any FAILED in
 * the last 5 minutes". A `runningCount` of 0 + `recentFailureCount` of 0
 * renders as the muted "idle" pill; positive `runningCount` lights the
 * primary badge; positive `recentFailureCount` adds a red dot.
 */
data class NavJobIndicatorBadge(
    val runningCount: Long,
    val recentFailureCount: Long,
) {
    val hasRunning: Boolean get() = runningCount > 0
    val hasRecentFailure: Boolean get() = recentFailureCount > 0
}

/**
 * One row in the dropdown panel.
 *
 * @property source Optional human-readable source label (current step name
 *   from the JobRun, e.g. "INBOX" for email or "/home/sean/docs" for crawl).
 * @property detailLink Stable URL the operator can click to land on the
 *   most relevant detail page for this job — see [NavJobIndicatorService.detailLinkFor].
 */
data class NavJobIndicatorRow(
    val jobRunId: Long?,
    val jobName: String,
    val source: String?,
    val startedAt: OffsetDateTime?,
    val status: String,
    val detailLink: String,
)
