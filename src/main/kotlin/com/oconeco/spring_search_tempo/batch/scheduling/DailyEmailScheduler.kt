package com.oconeco.spring_search_tempo.batch.scheduling

import com.oconeco.spring_search_tempo.base.config.EmailConfiguration
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs enabled email syncs on a scheduled basis and can perform startup catch-up.
 *
 * Mirrors [DailyCrawlScheduler] for file crawling but targets email accounts.
 * Enabled via `app.scheduling.email.enabled=true`.
 */
@Component
class DailyEmailScheduler(
    private val emailCrawlOrchestrator: EmailCrawlOrchestrator,
    private val emailConfiguration: EmailConfiguration,
    private val jobRunRepository: JobRunRepository,
    private val schedulingProperties: EmailSchedulingProperties
) {
    companion object {
        private const val EMAIL_JOB_PREFIX = "emailQuickSync"
        private const val LOOKBACK_DAYS = 10L
        private const val CRON_ITERATION_GUARD = 20000
        private val log = LoggerFactory.getLogger(DailyEmailScheduler::class.java)
    }

    private val launchGuard = AtomicBoolean(false)

    @Scheduled(
        cron = "\${app.scheduling.email.cron:0 0 */4 * * *}",
        zone = "\${app.scheduling.email.zone:}"
    )
    fun runOnSchedule() {
        if (!schedulingProperties.enabled) {
            return
        }
        if (!emailConfiguration.enabled) {
            log.debug("Email scheduling enabled but email crawling disabled in configuration")
            return
        }
        triggerEmailSync("scheduled-cron")
    }

    @EventListener(ApplicationReadyEvent::class)
    fun runMissedScheduleOnStartup() {
        if (!schedulingProperties.enabled || !schedulingProperties.runMissedOnStartup) {
            return
        }
        if (!emailConfiguration.enabled) {
            log.debug("Email scheduling enabled but email crawling disabled in configuration")
            return
        }

        val zoneId = resolveZoneId()
        val now = ZonedDateTime.now(zoneId)
        val lastScheduled = findMostRecentScheduledTime(now) ?: run {
            log.warn("Unable to evaluate startup catch-up: no scheduled instant found for cron '{}'",
                schedulingProperties.cron)
            return
        }

        val ageMinutes = Duration.between(lastScheduled, now).toMinutes()
        if (ageMinutes < schedulingProperties.startupGraceMinutes) {
            log.info("Skipping email startup catch-up (within grace window: {}m < {}m)",
                ageMinutes, schedulingProperties.startupGraceMinutes)
            return
        }

        // Check if any email job ran since the scheduled time
        val alreadyRanSinceSchedule = jobRunRepository.existsByJobNameStartingWithAndStartTimeGreaterThanEqual(
            EMAIL_JOB_PREFIX,
            lastScheduled.toOffsetDateTime()
        )
        if (alreadyRanSinceSchedule) {
            log.info("No email startup catch-up needed; email jobs already ran since {}",
                lastScheduled)
            return
        }

        log.info("Missed scheduled email sync detected (last scheduled: {}). Triggering catch-up run now.", lastScheduled)
        triggerEmailSync("startup-catchup")
    }

    /**
     * Trigger email sync programmatically (used by [EmailAutoTriggerListener]).
     */
    fun triggerEmailSync(trigger: String) {
        if (!launchGuard.compareAndSet(false, true)) {
            log.info("Skipping {} email sync trigger: scheduler is already launching a sync batch", trigger)
            return
        }

        try {
            // Check if any email job is already running
            if (jobRunRepository.existsByJobNameStartingWithAndRunStatus(EMAIL_JOB_PREFIX, RunStatus.RUNNING)) {
                log.info("Skipping {} email sync trigger: email job already has a RUNNING execution", trigger)
                return
            }

            val results = emailCrawlOrchestrator.runQuickSync()
            log.info("Email scheduler trigger '{}' started {} email sync job(s)", trigger, results.size)
        } catch (e: Exception) {
            log.error("Scheduled email sync trigger '{}' failed: {}", trigger, e.message, e)
        } finally {
            launchGuard.set(false)
        }
    }

    private fun resolveZoneId(): ZoneId {
        val configured = schedulingProperties.zone.trim()
        return if (configured.isBlank()) ZoneId.systemDefault() else ZoneId.of(configured)
    }

    private fun findMostRecentScheduledTime(now: ZonedDateTime): ZonedDateTime? {
        val cronExpression = try {
            CronExpression.parse(schedulingProperties.cron)
        } catch (e: Exception) {
            log.error("Invalid email scheduler cron expression '{}': {}",
                schedulingProperties.cron, e.message)
            return null
        }

        var last: ZonedDateTime? = null
        var cursor = now.minusDays(LOOKBACK_DAYS)
        var count = 0

        while (count < CRON_ITERATION_GUARD) {
            val next = cronExpression.next(cursor) ?: break
            if (next.isAfter(now)) {
                break
            }
            last = next
            cursor = next
            count++
        }

        return last
    }
}
