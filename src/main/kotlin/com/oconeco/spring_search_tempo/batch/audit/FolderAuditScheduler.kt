package com.oconeco.spring_search_tempo.batch.audit

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Weekly @Scheduled trigger for the filesystem folder audit (issue #105).
 *
 * Wraps [FolderAuditService.startFilesystemAuditRun] — the same kick-off
 * path the REST endpoint and admin button use — so the scheduled fire and
 * a manual click share the same dedup logic (one active run at a time).
 *
 * Default cron `0 0 3 * * SUN` (Sundays at 03:00). The audit is a sanity
 * check, not load-bearing — pick an off-hours slot so any disk I/O it
 * stirs up doesn't compete with live work.
 *
 * Idempotency:
 *  - If a run is already in progress, [FolderAuditService] throws
 *    [JobExecutionAlreadyRunningException]; we catch, log a warning, and
 *    bump the `tempo.audit.scheduler.skipped` counter so an operator can
 *    see overlap in Grafana.
 *  - Any other exception is logged and swallowed — a weekly trigger that
 *    propagates would tear down the scheduler thread silently and we'd
 *    miss the next fire too.
 *
 * Enable/disable via `app.audit.weekly-enabled` (default true). The cron
 * is still evaluated when disabled; we just no-op at fire time so tests
 * that pin the bean don't need to override the cron itself.
 */
@Component
class FolderAuditScheduler(
    private val folderAuditService: FolderAuditService,
    private val auditProperties: AuditProperties,
    private val meterRegistry: MeterRegistry
) {

    companion object {
        private val log = LoggerFactory.getLogger(FolderAuditScheduler::class.java)
        const val SKIPPED_METRIC = "tempo.audit.scheduler.skipped"
        const val TRIGGERED_METRIC = "tempo.audit.scheduler.triggered"
    }

    @Scheduled(cron = "\${app.audit.weekly-cron:0 0 3 * * SUN}")
    fun runWeeklyAudit() {
        if (!auditProperties.weeklyEnabled) {
            log.debug("Weekly folder audit scheduler is disabled; skipping fire")
            return
        }
        log.info("Weekly folder audit fire (cron='{}')", auditProperties.weeklyCron)
        try {
            val runId = folderAuditService.startFilesystemAuditRun()
            meterRegistry.counter(TRIGGERED_METRIC).increment()
            log.info("Weekly folder audit launched: runId={}", runId)
        } catch (e: JobExecutionAlreadyRunningException) {
            meterRegistry.counter(SKIPPED_METRIC, "reason", "already-running").increment()
            log.warn("Weekly folder audit skipped — a run is already in progress: {}", e.message)
        } catch (e: Exception) {
            meterRegistry.counter(SKIPPED_METRIC, "reason", "error").increment()
            log.error("Weekly folder audit failed to launch", e)
        }
    }
}
