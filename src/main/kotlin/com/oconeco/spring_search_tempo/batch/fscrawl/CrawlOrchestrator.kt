package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Orchestrates the execution of multiple file system crawls.
 * Supports both parallel and sequential crawl execution based on configuration.
 */
@Service
class CrawlOrchestrator(
    private val jobLauncher: JobLauncher,
    private val crawlConfigService: CrawlConfigService,
    private val jobBuilder: FsCrawlJobBuilder
) {
    companion object {
        private val log = LoggerFactory.getLogger(CrawlOrchestrator::class.java)
    }

    /**
     * Execute all enabled crawls according to their configuration.
     * Parallel crawls are executed concurrently, sequential crawls run one at a time.
     *
     * @return Map of crawl names to their job execution results
     */
    fun executeAllCrawls(): Map<String, JobExecution> {
        val enabledCrawls = crawlConfigService.getEnabledCrawls()

        if (enabledCrawls.isEmpty()) {
            log.warn("No enabled crawls found in configuration")
            return emptyMap()
        }

        log.info("Executing {} enabled crawl(s)", enabledCrawls.size)

        val defaults = crawlConfigService.getDefaults()

        // Separate parallel and sequential crawls
        val parallelCrawls = enabledCrawls.filter {
            it.getParallel(defaults)
        }
        val sequentialCrawls = enabledCrawls.filter {
            !it.getParallel(defaults)
        }

        val results = mutableMapOf<String, JobExecution>()

        // Execute parallel crawls concurrently
        if (parallelCrawls.isNotEmpty()) {
            log.info("Executing {} parallel crawl(s): {}",
                parallelCrawls.size,
                parallelCrawls.joinToString { it.name })

            val futures = parallelCrawls.map { crawl ->
                CompletableFuture.supplyAsync {
                    crawl.name to executeCrawl(crawl)
                }
            }

            // Wait for all parallel crawls to complete
            futures.forEach { future ->
                val (name, execution) = future.get()
                results[name] = execution
            }
        }

        // Execute sequential crawls one at a time
        if (sequentialCrawls.isNotEmpty()) {
            log.info("Executing {} sequential crawl(s): {}",
                sequentialCrawls.size,
                sequentialCrawls.joinToString { it.name })

            sequentialCrawls.forEach { crawl ->
                val execution = executeCrawl(crawl)
                results[crawl.name] = execution
            }
        }

        // Log summary
        val successful = results.values.count { it.status.isUnsuccessful.not() }
        val failed = results.values.count { it.status.isUnsuccessful }

        log.info("Crawl execution complete: {} successful, {} failed", successful, failed)

        results.forEach { (name, execution) ->
            val duration = if (execution.endTime != null && execution.startTime != null) {
                val durationMs = Duration.between(execution.startTime, execution.endTime).toMillis()
                "completed in ${durationMs}ms"
            } else {
                "in progress"
            }
            log.info("  - {}: {} ({})", name, execution.status, duration)
        }

        return results
    }

    /**
     * Execute a single crawl.
     *
     * @param crawl The crawl definition to execute
     * @return The job execution result
     */
    fun executeCrawl(crawl: CrawlDefinition): JobExecution {
        log.info("Starting crawl: {} ({})", crawl.name, crawl.label)

        val job = jobBuilder.buildJob(crawl)

        // Create unique job parameters to allow multiple executions
        val params = JobParametersBuilder()
            .addString("crawlName", crawl.name)
            .addLong("timestamp", System.currentTimeMillis())
            .addString("startPaths", crawl.startPaths.joinToString(","))
            .toJobParameters()

        return try {
            val execution = jobLauncher.run(job, params)
            log.info("Crawl {} completed with status: {}", crawl.name, execution.status)
            execution
        } catch (e: Exception) {
            log.error("Crawl {} failed with exception", crawl.name, e)
            throw e
        }
    }

    /**
     * Execute crawls by name.
     * Useful for triggering specific crawls on demand.
     *
     * @param crawlNames Names of crawls to execute
     * @return Map of crawl names to their job execution results
     */
    fun executeCrawlsByName(vararg crawlNames: String): Map<String, JobExecution> {
        val crawlsToExecute = crawlNames.mapNotNull { name ->
            crawlConfigService.getCrawlByName(name)
        }

        if (crawlsToExecute.isEmpty()) {
            log.warn("No crawls found with names: {}", crawlNames.joinToString())
            return emptyMap()
        }

        log.info("Executing {} requested crawl(s): {}",
            crawlsToExecute.size,
            crawlsToExecute.joinToString { it.name })

        return crawlsToExecute.associate { crawl ->
            crawl.name to executeCrawl(crawl)
        }
    }

    /**
     * Get all enabled crawl names.
     */
    fun getEnabledCrawls(): List<String> {
        return crawlConfigService.getEnabledCrawls()
            .map { it.name }
    }

    /**
     * Get all crawl names (enabled and disabled).
     */
    fun getAllCrawls(): List<String> {
        return crawlConfigService.getAllCrawls()
            .map { it.name }
    }
}
