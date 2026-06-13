package com.oconeco.spring_search_tempo.batch.config

import com.oconeco.spring_search_tempo.base.domain.JobLifecycleEvent
import com.oconeco.spring_search_tempo.base.domain.JobLifecycleEventType
import com.oconeco.spring_search_tempo.base.repos.JobLifecycleEventRepository
import com.oconeco.spring_search_tempo.base.service.BatchAdminService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Marks every in-process `STARTED` BatchJobExecution as FAILED before the
 * JVM exits cleanly (SIGTERM, normal `Ctrl-C`, devtools restart, container
 * stop). Belt-and-braces companion to [JobExecutionAdvisoryLockListener]
 * and [OrphanedJobExecutionReaper] (issue #64).
 *
 * Why this layer exists even though the lock listener releases on JVM
 * death: clean shutdown happens fast, and the operator typically restarts
 * immediately. Marking the rows FAILED *here* means the next boot doesn't
 * have to do reaper work to clear them — and the dashboard reflects
 * "FAILED, app shutdown" rather than "STARTED, then reaper fixed it on
 * boot." Better user-facing semantics for the common path.
 *
 * Does NOT fire on `kill -9`, OOM-killer, or power loss. Those paths fall
 * through to the advisory-lock reaper at next boot.
 *
 * Audit trail (issue #75): each observed JobExecution produces one row in
 * `job_lifecycle_event` (event_type=SHUTDOWN) with the resulting action:
 *
 *   - `stopped` — markJobAsFailed succeeded; row is FAILED in `BATCH_JOB_EXECUTION`.
 *   - `abandoned` — markJobAsFailed threw; row is left for the next-boot reaper.
 *   - `shutdown_too_fast` — reserved for future use; recorded if the hook
 *     observes the id but its grace window expires before it can act.
 */
@Component
class RunningJobShutdownHook(
    private val advisoryLockListener: JobExecutionAdvisoryLockListener,
    private val batchAdminService: BatchAdminService,
    private val jobExplorer: JobExplorer,
    private val jobLifecycleEventRepository: JobLifecycleEventRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(RunningJobShutdownHook::class.java)
    }

    @PreDestroy
    fun markRunningJobsFailedOnShutdown() {
        val ids = advisoryLockListener.heldExecutionIds()
        if (ids.isEmpty()) {
            return
        }
        log.warn(
            "Shutdown hook: {} BatchJobExecution(s) still STARTED — marking FAILED before exit: {}",
            ids.size, ids
        )
        for (executionId in ids) {
            val execution = safeLookupExecution(executionId)
            try {
                val reason = "App shutdown: job did not complete before JVM stopped"
                val marked = batchAdminService.markJobAsFailed(executionId, reason)
                val action = if (marked) "stopped" else "abandoned"
                val details = if (marked) reason else "$reason (markJobAsFailed returned false)"
                recordShutdownEvent(executionId, execution, action, details)
            } catch (e: Exception) {
                log.warn("Shutdown hook: failed to mark executionId={} FAILED: {}", executionId, e.message)
                recordShutdownEvent(
                    executionId,
                    execution,
                    action = "abandoned",
                    details = "markJobAsFailed threw: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun safeLookupExecution(executionId: Long): JobExecution? = try {
        jobExplorer.getJobExecution(executionId)
    } catch (e: Exception) {
        log.debug("Shutdown hook: jobExplorer.getJobExecution({}) failed: {}", executionId, e.message)
        null
    }

    private fun recordShutdownEvent(
        executionId: Long,
        execution: JobExecution?,
        action: String,
        details: String
    ) {
        try {
            val event = JobLifecycleEvent().apply {
                this.eventTime = OffsetDateTime.now()
                this.eventType = JobLifecycleEventType.SHUTDOWN
                this.actionTaken = action
                this.jobExecutionId = executionId
                this.jobName = execution?.jobInstance?.jobName ?: "unknown"
                this.accountId = execution?.jobParameters?.getString("accountId")?.toLongOrNull()
                this.originalStartedAt = execution?.startTime
                    ?.atZone(ZoneId.systemDefault())
                    ?.toOffsetDateTime()
                this.details = details
            }
            jobLifecycleEventRepository.save(event)
        } catch (e: Exception) {
            log.warn(
                "Shutdown hook: failed to persist job_lifecycle_event for executionId={}: {}",
                executionId, e.message
            )
        }
    }
}
