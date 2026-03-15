package com.oconeco.spring_search_tempo.batch.scheduling

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Configuration for scheduled email sync orchestration.
 */
@Configuration
@ConfigurationProperties(prefix = "app.scheduling.email")
data class EmailSchedulingProperties(
    /**
     * Enables/disables cron-based email sync scheduling.
     */
    var enabled: Boolean = false,
    /**
     * Cron expression for scheduled email sync runs.
     * Default is every 4 hours.
     */
    var cron: String = "0 0 */4 * * *",
    /**
     * Timezone for cron evaluation. Blank uses JVM default timezone.
     */
    var zone: String = "",
    /**
     * When true, evaluate on startup whether the most recent scheduled run was missed.
     */
    var runMissedOnStartup: Boolean = true,
    /**
     * Skip catch-up if startup happens very close to the scheduled instant.
     */
    var startupGraceMinutes: Long = 5,
    /**
     * When true, trigger email sync after file crawl job completes successfully.
     */
    var triggerAfterCrawl: Boolean = false
)
