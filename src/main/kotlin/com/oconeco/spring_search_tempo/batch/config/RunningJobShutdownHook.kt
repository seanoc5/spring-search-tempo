package com.oconeco.spring_search_tempo.batch.config

import com.oconeco.spring_search_tempo.base.service.BatchAdminService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
 */
@Component
class RunningJobShutdownHook(
    private val advisoryLockListener: JobExecutionAdvisoryLockListener,
    private val batchAdminService: BatchAdminService
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
            try {
                batchAdminService.markJobAsFailed(
                    executionId,
                    "App shutdown: job did not complete before JVM stopped"
                )
            } catch (e: Exception) {
                log.warn("Shutdown hook: failed to mark executionId={} FAILED: {}", executionId, e.message)
            }
        }
    }
}
