package com.oconeco.spring_search_tempo.batch.scheduling

import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.model.MirrorConfigDTO
import com.oconeco.spring_search_tempo.batch.mirror.MirrorJobLauncher
import org.slf4j.LoggerFactory
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Per-MirrorConfig cron dispatcher (issue #38).
 *
 * Ticks on a fixed delay (default every minute), iterates enabled
 * `MirrorConfig` rows that have a `cronSchedule`, and dispatches a
 * `mirrorJob` for any whose next cron boundary (after `lastDispatchedAt`)
 * has elapsed. Mirrors the per-account scheduling pattern used by
 * [MultiAccountEmailScheduler] / [com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator.runDueAccounts].
 *
 * Acceptance criteria from issue #38:
 *  - Manual launches via `MirrorJobLauncher.launch(...)` remain the
 *    single point that talks to Spring Batch; the scheduler is purely
 *    additive.
 *  - If a mirror is already running for the same destination account,
 *    skip and log at WARN (don't queue overlapping mirror jobs for the
 *    same destination — see brief acceptance #5).
 *  - Globally disable via `app.mirror.scheduler.enabled=false`.
 */
@Component
class MirrorScheduler(
    private val mirrorConfigService: MirrorConfigService,
    private val mirrorJobLauncher: MirrorJobLauncher,
    private val jobExplorer: JobExplorer,
    private val properties: MirrorSchedulerProperties,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    companion object {
        private val log = LoggerFactory.getLogger(MirrorScheduler::class.java)
    }

    @Scheduled(fixedDelayString = "\${app.mirror.scheduler.tick-millis:60000}")
    fun tick() {
        if (!properties.enabled) {
            return
        }
        runDueMirrors(clock.instant())
    }

    /**
     * Visible for testing. Iterates enabled `MirrorConfig` rows and
     * dispatches any whose cron boundary has elapsed since
     * `lastDispatchedAt` (or epoch for never-dispatched configs).
     *
     * Returns one [MirrorDispatchResult] per evaluated config so callers
     * (tests, future REST surfacing) can see what fired and what was
     * skipped.
     */
    fun runDueMirrors(now: Instant): List<MirrorDispatchResult> {
        val enabled = mirrorConfigService.findEnabled()
        if (enabled.isEmpty()) {
            return emptyList()
        }

        val runningDestAccountIds = collectRunningMirrorDestAccountIds()
        val zone = ZoneId.systemDefault()
        val results = mutableListOf<MirrorDispatchResult>()

        for (mirror in enabled) {
            val mirrorId = mirror.id ?: continue
            val cronText = mirror.cronSchedule?.trim()
            if (cronText.isNullOrBlank()) {
                results += MirrorDispatchResult(mirrorId, mirror.name, MirrorDispatchOutcome.NO_SCHEDULE, null)
                continue
            }

            val cron = try {
                CronExpression.parse(cronText)
            } catch (e: IllegalArgumentException) {
                val reason = "invalid cron '$cronText': ${e.message}"
                log.warn("Skipping mirror {} (id={}) — {}", mirror.name, mirrorId, reason)
                results += MirrorDispatchResult(mirrorId, mirror.name, MirrorDispatchOutcome.INVALID_CRON, reason)
                continue
            }

            val anchor = (mirror.lastDispatchedAt
                ?: OffsetDateTime.ofInstant(Instant.EPOCH, zone))
                .atZoneSameInstant(zone)

            val nextBoundary = cron.next(anchor)
            val nowZdt = now.atZone(zone)
            if (nextBoundary == null || nextBoundary.isAfter(nowZdt)) {
                results += MirrorDispatchResult(mirrorId, mirror.name, MirrorDispatchOutcome.NOT_DUE, null)
                continue
            }

            // Acceptance #5: don't queue overlapping mirror jobs for the same destination account.
            val destAccountId = mirror.destAccountId
            if (destAccountId != null && destAccountId in runningDestAccountIds) {
                log.warn(
                    "Skipping mirror {} (id={}): another mirror is already running against destAccountId={}",
                    mirror.name, mirrorId, destAccountId
                )
                results += MirrorDispatchResult(
                    mirrorId, mirror.name, MirrorDispatchOutcome.SKIPPED_IN_PROGRESS,
                    "destAccountId=$destAccountId already has a running mirror"
                )
                continue
            }

            try {
                log.info(
                    "Dispatching mirror {} (id={}) — cron='{}', last={}, due={}",
                    mirror.name, mirrorId, cronText, mirror.lastDispatchedAt, nextBoundary
                )
                val execution = mirrorJobLauncher.launch(mirrorId, triggeredBy = "scheduler")
                mirrorConfigService.recordDispatched(mirrorId, OffsetDateTime.ofInstant(now, zone))
                if (destAccountId != null) {
                    runningDestAccountIds += destAccountId
                }
                results += MirrorDispatchResult(
                    mirrorId, mirror.name, MirrorDispatchOutcome.DISPATCHED,
                    "executionId=${execution.id}"
                )
            } catch (e: JobExecutionAlreadyRunningException) {
                log.warn(
                    "Mirror {} (id={}) already has a running execution; skipping this tick",
                    mirror.name, mirrorId
                )
                results += MirrorDispatchResult(
                    mirrorId, mirror.name, MirrorDispatchOutcome.SKIPPED_IN_PROGRESS, e.message
                )
            } catch (e: Exception) {
                log.error("Failed to dispatch mirror {} (id={}): {}", mirror.name, mirrorId, e.message, e)
                results += MirrorDispatchResult(
                    mirrorId, mirror.name, MirrorDispatchOutcome.ERROR, e.message
                )
                // Continue iterating — sibling mirrors must not be blocked.
            }
        }

        return results
    }

    private fun collectRunningMirrorDestAccountIds(): MutableSet<Long> {
        val running = jobExplorer.findRunningJobExecutions("mirrorJob")
        if (running.isEmpty()) return mutableSetOf()
        // Resolve each running execution's mirrorConfigId → destAccountId via the service.
        val destIds = mutableSetOf<Long>()
        for (exec in running) {
            val mirrorConfigId = exec.jobParameters.getLong("mirrorConfigId") ?: continue
            val cfg: MirrorConfigDTO = mirrorConfigService.findByIdOrNull(mirrorConfigId) ?: continue
            cfg.destAccountId?.let { destIds += it }
        }
        return destIds
    }
}

@Configuration
@ConfigurationProperties(prefix = "app.mirror.scheduler")
data class MirrorSchedulerProperties(
    /**
     * Master switch for [MirrorScheduler]. Default ON. Tests that don't
     * exercise scheduling should set this to false to suppress the tick.
     */
    var enabled: Boolean = true,
    /**
     * Fixed delay between scheduler ticks, in milliseconds. Default 60_000
     * (1 minute). Exposed for tests that need to wind the clock forward
     * quickly.
     */
    var tickMillis: Long = 60_000
)

/**
 * Outcome for one mirror in a [MirrorScheduler.runDueMirrors] sweep.
 */
data class MirrorDispatchResult(
    val mirrorConfigId: Long,
    val name: String?,
    val outcome: MirrorDispatchOutcome,
    val detail: String?
)

enum class MirrorDispatchOutcome {
    /** Mirror's cron boundary elapsed and the job launched successfully. */
    DISPATCHED,
    /** Mirror has no cron expression configured. */
    NO_SCHEDULE,
    /** Mirror's next cron boundary is still in the future. */
    NOT_DUE,
    /** Mirror's `cronSchedule` string is invalid; not dispatched. */
    INVALID_CRON,
    /** A mirror is already running against the same destination account, or
     *  the launcher rejected the dispatch as already-running. */
    SKIPPED_IN_PROGRESS,
    /** Exception during dispatch; the loop continues for sibling mirrors. */
    ERROR
}
