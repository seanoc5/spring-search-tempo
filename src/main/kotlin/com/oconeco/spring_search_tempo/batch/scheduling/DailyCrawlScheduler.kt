package com.oconeco.spring_search_tempo.batch.scheduling

import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import com.oconeco.spring_search_tempo.batch.fscrawl.CrawlOrchestrator
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
 * Runs enabled filesystem crawls on a daily schedule and can perform startup catch-up.
 */
@Component
class DailyCrawlScheduler(
    private val crawlOrchestrator: CrawlOrchestrator,
    private val jobRunRepository: JobRunRepository,
    private val schedulingProperties: CrawlSchedulingProperties
) {
    companion object {
        private const val CRAWL_JOB_NAME = "fsCrawlJob"
        private const val LOOKBACK_DAYS = 10L
        private const val CRON_ITERATION_GUARD = 20000
        private val log = LoggerFactory.getLogger(DailyCrawlScheduler::class.java)
    }

    private val launchGuard = AtomicBoolean(false)

    @Scheduled(
        cron = "\${app.scheduling.crawl.cron:0 0 1 * * *}",
        zone = "\${app.scheduling.crawl.zone:}"
    )
    fun runOnSchedule() {
        if (!schedulingProperties.enabled) {
            return
        }
        triggerCrawl("scheduled-cron")
    }

    @EventListener(ApplicationReadyEvent::class)
    fun runMissedScheduleOnStartup() {
        if (!schedulingProperties.enabled || !schedulingProperties.runMissedOnStartup) {
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
            log.info("Skipping startup catch-up (within grace window: {}m < {}m)",
                ageMinutes, schedulingProperties.startupGraceMinutes)
            return
        }

        val alreadyRanSinceSchedule = jobRunRepository.existsByJobNameAndStartTimeGreaterThanEqual(
            CRAWL_JOB_NAME,
            lastScheduled.toOffsetDateTime()
        )
        if (alreadyRanSinceSchedule) {
            log.info("No startup catch-up needed; '{}' already ran since {}",
                CRAWL_JOB_NAME, lastScheduled)
            return
        }

        log.info("Missed scheduled crawl detected (last scheduled: {}). Triggering catch-up run now.", lastScheduled)
        triggerCrawl("startup-catchup")
    }

    private fun triggerCrawl(trigger: String) {
        if (!launchGuard.compareAndSet(false, true)) {
            log.info("Skipping {} crawl trigger: scheduler is already launching a crawl batch", trigger)
            return
        }

        try {
            if (jobRunRepository.existsByJobNameAndRunStatus(CRAWL_JOB_NAME, RunStatus.RUNNING)) {
                log.info("Skipping {} crawl trigger: '{}' already has a RUNNING execution",
                    trigger, CRAWL_JOB_NAME)
                return
            }

            val results = crawlOrchestrator.executeAllCrawls()
            log.info("Crawl scheduler trigger '{}' started {} crawl job(s)", trigger, results.size)
        } catch (e: Exception) {
            log.error("Scheduled crawl trigger '{}' failed: {}", trigger, e.message, e)
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
            log.error("Invalid crawl scheduler cron expression '{}': {}",
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
