package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.stereotype.Component


/**
 * Job execution listener that tracks OneDrive sync job runs in the JobRun entity.
 * Creates a JobRun record at job start and updates it at job completion.
 */
@Component
class OneDriveJobRunTrackingListener(
    private val jobRunService: JobRunService
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveJobRunTrackingListener::class.java)
        const val JOB_RUN_ID_KEY = "jobRunId"
        const val ACCOUNT_ID_KEY = "accountId"
        const val ACCOUNT_EMAIL_KEY = "accountEmail"
        const val EXPECTED_TOTAL_KEY = "expectedTotal"
    }

    override fun beforeJob(jobExecution: JobExecution) {
        val jobName = jobExecution.jobInstance.jobName
        val accountId = jobExecution.jobParameters.getString(ACCOUNT_ID_KEY)
        val accountEmail = jobExecution.jobParameters.getString(ACCOUNT_EMAIL_KEY)
        val expectedTotal = jobExecution.jobParameters.getLong(EXPECTED_TOTAL_KEY)

        try {
            val label = accountEmail ?: "OneDrive Account $accountId"
            val jobRunDTO = jobRunService.startJobRunWithoutConfig(jobName, label)

            jobExecution.executionContext.putLong(JOB_RUN_ID_KEY, jobRunDTO.id!!)

            if (expectedTotal != null && expectedTotal > 0) {
                jobRunService.setExpectedTotal(jobRunDTO.id!!, expectedTotal)
                log.debug("Set expectedTotal={} for jobRunId={}", expectedTotal, jobRunDTO.id)
            }

            log.info("Started OneDrive job run tracking: jobRunId={}, jobName={}, account={}",
                jobRunDTO.id, jobName, accountEmail ?: accountId)
        } catch (e: Exception) {
            log.error("Failed to start OneDrive job run tracking for job: {}", jobName, e)
        }
    }

    override fun afterJob(jobExecution: JobExecution) {
        val jobRunId = jobExecution.executionContext.getLong(JOB_RUN_ID_KEY, -1L)

        if (jobRunId > 0) {
            try {
                var itemsDiscovered = 0L
                var itemsProcessed = 0L
                var itemsError = 0L

                for (stepExecution in jobExecution.stepExecutions) {
                    itemsDiscovered += stepExecution.readCount
                    itemsProcessed += stepExecution.writeCount
                }

                jobRunService.updateJobRunStats(
                    jobRunId = jobRunId,
                    filesDiscovered = itemsDiscovered,
                    filesNew = itemsProcessed,
                    filesError = itemsError
                )

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

                log.info("Completed OneDrive job run tracking: jobRunId={}, status={}, items={}",
                    jobRunId, runStatus, itemsDiscovered)
            } catch (e: Exception) {
                log.error("Failed to complete OneDrive job run tracking for jobRunId: {}", jobRunId, e)
            }
        }
    }
}
