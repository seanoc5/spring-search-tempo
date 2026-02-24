package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration for file system crawl batch jobs.
 *
 * NOTE: Jobs are no longer auto-run on startup. They are triggered via:
 * - UI: CrawlConfigController.runCrawl()
 * - API: CrawlOrchestrator endpoints
 *
 * This configuration provides a no-op placeholder bean to satisfy any
 * Spring Batch requirements without actually processing any paths.
 */
@Configuration
class FsCrawlJobConfiguration(
    private val crawlConfigService: CrawlConfigService,
    private val jobBuilder: FsCrawlJobBuilder
) {
    companion object {
        private val log = LoggerFactory.getLogger(FsCrawlJobConfiguration::class.java)
    }

    /**
     * Placeholder job bean. Not intended for actual execution.
     * Real crawl jobs are built dynamically via FsCrawlJobBuilder with specific
     * crawl configurations from the database.
     *
     * This bean exists only to satisfy Spring Batch's expectation of a job bean.
     * With spring.batch.job.enabled=false, this won't auto-run.
     */
    @Bean
    fun fsCrawlJob(): Job {
        // Use a non-existent path that will immediately complete with no items
        val noopCrawl = com.oconeco.spring_search_tempo.base.config.CrawlDefinition(
            name = "NOOP_PLACEHOLDER",
            label = "Placeholder (not for execution)",
            enabled = false,
            startPaths = emptyList(),  // No paths = no processing
            maxDepth = 0,
            followLinks = false
        )
        log.debug("Creating placeholder fsCrawlJob bean (not for execution)")
        return jobBuilder.buildJob(noopCrawl)
    }
}
