package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.batch.core.StepExecution
import org.springframework.stereotype.Component

/**
 * Job execution listener that tracks job runs in the JobRun entity.
 * Creates a JobRun record at job start and updates it at job completion.
 */
@Component
class JobRunTrackingListener(
    private val jobRunService: JobRunService
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(JobRunTrackingListener::class.java)
        const val JOB_RUN_ID_KEY = "jobRunId"
        const val CRAWL_CONFIG_ID_KEY = "crawlConfigId"
    }

    override fun beforeJob(jobExecution: JobExecution) {
        val jobName = jobExecution.jobInstance.jobName
        val crawlConfigIdParam = jobExecution.jobParameters.getString(CRAWL_CONFIG_ID_KEY)

        if (crawlConfigIdParam != null) {
            try {
                val crawlConfigId = crawlConfigIdParam.toLong()
                val jobRunDTO = jobRunService.startJobRun(crawlConfigId, jobName)

                // Store job run ID in execution context for access by steps
                jobExecution.executionContext.putLong(JOB_RUN_ID_KEY, jobRunDTO.id!!)

                log.info("Started job run tracking: jobRunId={}, crawlConfigId={}, jobName={}",
                    jobRunDTO.id, crawlConfigId, jobName)
            } catch (e: Exception) {
                log.error("Failed to start job run tracking for crawlConfigId: {}", crawlConfigIdParam, e)
            }
        } else {
            log.warn("No crawlConfigId parameter found for job: {}. Job run will not be tracked.", jobName)
        }
    }

    override fun afterJob(jobExecution: JobExecution) {
        val jobRunId = jobExecution.executionContext.getLong(JOB_RUN_ID_KEY, -1L)

        if (jobRunId > 0) {
            try {
                // Aggregate statistics from all step executions
                var filesDiscovered = 0L
                var filesNew = 0L
                var filesUpdated = 0L
                var filesSkipped = 0L
                var filesError = 0L
                var filesAccessDenied = 0L
                var foldersDiscovered = 0L
                var foldersNew = 0L
                var foldersUpdated = 0L
                var foldersSkipped = 0L

                for (stepExecution in jobExecution.stepExecutions) {
                    filesDiscovered += stepExecution.executionContext.getLong("filesDiscovered", 0L)
                    filesNew += stepExecution.executionContext.getLong("filesNew", 0L)
                    filesUpdated += stepExecution.executionContext.getLong("filesUpdated", 0L)
                    filesSkipped += stepExecution.executionContext.getLong("filesSkipped", 0L)
                    filesError += stepExecution.executionContext.getLong("filesError", 0L)
                    filesAccessDenied += stepExecution.executionContext.getLong("filesAccessDenied", 0L)
                    foldersDiscovered += stepExecution.executionContext.getLong("foldersDiscovered", 0L)
                    foldersNew += stepExecution.executionContext.getLong("foldersNew", 0L)
                    foldersUpdated += stepExecution.executionContext.getLong("foldersUpdated", 0L)
                    foldersSkipped += stepExecution.executionContext.getLong("foldersSkipped", 0L)
                }

                // Update job run statistics
                jobRunService.updateJobRunStats(
                    jobRunId = jobRunId,
                    filesDiscovered = filesDiscovered,
                    filesNew = filesNew,
                    filesUpdated = filesUpdated,
                    filesSkipped = filesSkipped,
                    filesError = filesError,
                    filesAccessDenied = filesAccessDenied,
                    foldersDiscovered = foldersDiscovered,
                    foldersNew = foldersNew,
                    foldersUpdated = foldersUpdated,
                    foldersSkipped = foldersSkipped
                )

                // Determine final status
                val runStatus = when (jobExecution.status) {
                    org.springframework.batch.core.BatchStatus.COMPLETED -> RunStatus.COMPLETED
                    org.springframework.batch.core.BatchStatus.FAILED -> RunStatus.FAILED
                    org.springframework.batch.core.BatchStatus.STOPPED -> RunStatus.CANCELLED
                    else -> RunStatus.FAILED
                }

                val errorMessage = jobExecution.allFailureExceptions
                    .joinToString("; ") { it.message ?: "Unknown error" }
                    .takeIf { it.isNotEmpty() }

                jobRunService.completeJobRun(
                    jobRunId = jobRunId,
                    runStatus = runStatus,
                    errorMessage = errorMessage
                )

                log.info("Completed job run tracking: jobRunId={}, status={}, files={}, folders={}",
                    jobRunId, runStatus, filesDiscovered, foldersDiscovered)
            } catch (e: Exception) {
                log.error("Failed to complete job run tracking for jobRunId: {}", jobRunId, e)
            }
        }
    }

    /**
     * Get job run ID from current step execution context.
     * Steps can call this to get the jobRunId to set on entities.
     */
    fun getJobRunId(stepExecution: StepExecution): Long? {
        return stepExecution.jobExecution.executionContext.getLong(JOB_RUN_ID_KEY, -1L)
            .takeIf { it > 0 }
    }
}
