package com.oconeco.spring_search_tempo.batch.scheduling

import com.oconeco.spring_search_tempo.base.config.EmailConfiguration
import com.oconeco.spring_search_tempo.batch.emailcrawl.DispatchOutcome
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Per-account email sync scheduler (issue #2, ADR-004).
 *
 * Ticks every minute and asks [EmailCrawlOrchestrator] to dispatch any
 * accounts whose stored `cronSchedule` boundary has elapsed. The
 * orchestrator handles per-account failure isolation; this bean just
 * provides the tick.
 *
 * Coexists with [DailyEmailScheduler] (global cron) for one release so
 * existing `app.scheduling.email.cron` configurations don't silently
 * stop firing. New installs should disable [DailyEmailScheduler] and
 * rely on per-account schedules via this bean.
 */
@Component
class MultiAccountEmailScheduler(
    private val orchestrator: EmailCrawlOrchestrator,
    private val emailConfiguration: EmailConfiguration,
    private val properties: MultiAccountEmailSchedulingProperties,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    companion object {
        private val log = LoggerFactory.getLogger(MultiAccountEmailScheduler::class.java)
    }

    @Scheduled(fixedDelayString = "\${app.scheduling.email.multi-account.tick-millis:60000}")
    fun tick() {
        if (!properties.enabled) {
            return
        }
        if (!emailConfiguration.enabled) {
            log.trace("Multi-account scheduler enabled but email crawling disabled in configuration")
            return
        }

        val results = orchestrator.runDueAccounts(clock.instant())
        val dispatched = results.count { it.outcome == DispatchOutcome.DISPATCHED }
        if (dispatched > 0) {
            log.info("Multi-account email tick dispatched {} of {} enabled accounts",
                dispatched, results.size)
        }
    }
}

@Configuration
@ConfigurationProperties(prefix = "app.scheduling.email.multi-account")
data class MultiAccountEmailSchedulingProperties(
    /**
     * Enables the per-account cron scheduler. Default ON.
     * Set false to fall back to the legacy global-cron [DailyEmailScheduler].
     */
    var enabled: Boolean = true,
    /**
     * Fixed delay between scheduler ticks, in milliseconds. Default 60_000 (1 minute).
     * Exposed for tests that need to wind the clock forward quickly.
     */
    var tickMillis: Long = 60_000
)
