package com.oconeco.spring_search_tempo.batch.scheduling

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Configuration for scheduled crawl orchestration.
 */
@Configuration
@EnableScheduling
@ConfigurationProperties(prefix = "app.scheduling.crawl")
data class CrawlSchedulingProperties(
    /**
     * Enables/disables cron-based crawl scheduling.
     */
    var enabled: Boolean = false,
    /**
     * Cron expression for scheduled crawl runs (server-side).
     * Default is once daily at 01:00.
     */
    var cron: String = "0 0 1 * * *",
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
    var startupGraceMinutes: Long = 5
)
