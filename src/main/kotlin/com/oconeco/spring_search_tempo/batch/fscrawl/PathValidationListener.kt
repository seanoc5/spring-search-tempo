package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.service.StartPathValidator
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import java.nio.file.Path

/**
 * Job execution listener that validates start paths and records warnings.
 * Creates warnings for missing, unreadable, or invalid paths before the crawl starts.
 *
 * @param startPaths The list of paths to validate
 * @param jobRunService Service to persist warnings to JobRun entity
 */
class PathValidationListener(
    private val startPaths: List<Path>,
    private val jobRunService: JobRunService
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(PathValidationListener::class.java)
        const val PATH_WARNINGS_KEY = "pathWarnings"
        const val VALID_PATHS_KEY = "validPaths"
    }

    override fun beforeJob(jobExecution: JobExecution) {
        log.info("Validating {} start paths before job execution", startPaths.size)

        // Validate paths
        val (validPaths, warnings) = StartPathValidator.validateAndFilter(startPaths)

        // Store valid paths in execution context for readers to use
        jobExecution.executionContext.put(VALID_PATHS_KEY, validPaths.map { it.toString() })

        // If there are warnings, log and persist them
        if (warnings.isNotEmpty()) {
            log.warn("Path validation found {} warnings:", warnings.size)
            warnings.forEach { log.warn("  - {}", it) }

            // Store warnings in execution context
            jobExecution.executionContext.put(PATH_WARNINGS_KEY, warnings)

            // Persist warnings to JobRun if we have a job run ID
            val jobRunId = jobExecution.executionContext.getLong(JobRunTrackingListener.JOB_RUN_ID_KEY, -1L)
            if (jobRunId > 0) {
                try {
                    val warningMessage = warnings.joinToString("\n")
                    jobRunService.addWarning(jobRunId, warningMessage)
                    log.info("Persisted {} path warnings to JobRun {}", warnings.size, jobRunId)
                } catch (e: Exception) {
                    log.error("Failed to persist path warnings to JobRun {}: {}", jobRunId, e.message)
                }
            }
        }

        if (validPaths.isEmpty() && startPaths.isNotEmpty()) {
            log.error("ALL start paths are invalid! Job will process no directories.")
        } else {
            log.info("Path validation complete: {} of {} paths are valid",
                validPaths.size, startPaths.size)
        }
    }

    override fun afterJob(jobExecution: JobExecution) {
        // No action needed after job
    }
}
