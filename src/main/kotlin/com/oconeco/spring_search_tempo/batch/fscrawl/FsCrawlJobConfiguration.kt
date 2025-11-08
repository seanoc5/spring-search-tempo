package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration for file system crawl batch jobs.
 * Uses CrawlConfigService to build jobs for each enabled crawl.
 *
 * The fsCrawlJob bean is the default job that Spring Batch runs on startup.
 * It executes the first enabled crawl definition from application.yml.
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
     * Default batch job that runs on startup.
     * Uses the first enabled crawl definition, or defaults to WORK crawl if none enabled.
     */
    @Bean
    fun fsCrawlJob(): Job {
        val firstEnabledCrawl = crawlConfigService.getEnabledCrawls().firstOrNull()

        if (firstEnabledCrawl == null) {
            log.warn("No enabled crawls found in configuration. Using default settings.")
            // Create a minimal default crawl
            val defaultCrawl = com.oconeco.spring_search_tempo.base.config.CrawlDefinition(
                name = "DEFAULT",
                label = "Default Crawl",
                enabled = true,
                startPaths = listOf("/opt/work"),
                maxDepth = 5,
                followLinks = false
            )
            return jobBuilder.buildJob(defaultCrawl)
        }

        log.info("Building default fsCrawlJob for crawl: {} ({})",
            firstEnabledCrawl.name, firstEnabledCrawl.label)

        return jobBuilder.buildJob(firstEnabledCrawl)
    }
}
