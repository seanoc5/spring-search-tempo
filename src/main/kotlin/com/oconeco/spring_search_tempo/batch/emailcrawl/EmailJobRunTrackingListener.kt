package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.stereotype.Component

/**
 * Job execution listener that tracks email sync job runs in the JobRun entity.
 * Creates a JobRun record at job start and updates it at job completion.
 * Also updates the EmailAccount sync state (lastQuickSyncAt, UIDs) on completion.
 *
 * Similar to JobRunTrackingListener but works without requiring a CrawlConfig.
 */
@Component
class EmailJobRunTrackingListener(
    private val jobRunService: JobRunService,
    private val emailAccountService: EmailAccountService
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(EmailJobRunTrackingListener::class.java)
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
            val label = accountEmail ?: "Email Account $accountId"
            val jobRunDTO = jobRunService.startJobRunWithoutConfig(jobName, label)

            // Store job run ID in execution context for access by steps
            jobExecution.executionContext.putLong(JOB_RUN_ID_KEY, jobRunDTO.id!!)

            // Set expected total if provided (from pre-fetched IMAP folder counts)
            if (expectedTotal != null && expectedTotal > 0) {
                jobRunService.setExpectedTotal(jobRunDTO.id!!, expectedTotal)
                log.debug("Set expectedTotal={} for jobRunId={}", expectedTotal, jobRunDTO.id)
            }

            log.info("Started email job run tracking: jobRunId={}, jobName={}, account={}, expectedTotal={}",
                jobRunDTO.id, jobName, accountEmail ?: accountId, expectedTotal)
        } catch (e: Exception) {
            log.error("Failed to start email job run tracking for job: {}", jobName, e)
        }
    }

    override fun afterJob(jobExecution: JobExecution) {
        val jobRunId = jobExecution.executionContext.getLong(JOB_RUN_ID_KEY, -1L)

        if (jobRunId > 0) {
            try {
                // Aggregate statistics from all step executions
                var messagesDiscovered = 0L
                var messagesNew = 0L
                var messagesUpdated = 0L
                var messagesError = 0L

                for (stepExecution in jobExecution.stepExecutions) {
                    messagesDiscovered += stepExecution.readCount
                    messagesNew += stepExecution.writeCount
                    // Could track more specific stats if needed
                }

                // Update job run with email-specific stats (using file counters for now)
                jobRunService.updateJobRunStats(
                    jobRunId = jobRunId,
                    filesDiscovered = messagesDiscovered,
                    filesNew = messagesNew,
                    filesError = messagesError
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

                log.info("Completed email job run tracking: jobRunId={}, status={}, messages={}",
                    jobRunId, runStatus, messagesDiscovered)

                // Update EmailAccount sync state (lastQuickSyncAt + per-folder UIDs)
                updateAccountSyncState(jobExecution, runStatus)

            } catch (e: Exception) {
                log.error("Failed to complete email job run tracking for jobRunId: {}", jobRunId, e)
            }
        }
    }

    /**
     * Extract per-folder highest UIDs from header sync steps and update the account.
     */
    private fun updateAccountSyncState(jobExecution: JobExecution, runStatus: RunStatus) {
        val accountIdStr = jobExecution.jobParameters.getString(ACCOUNT_ID_KEY) ?: return
        val accountId = accountIdStr.toLongOrNull() ?: return

        if (runStatus != RunStatus.COMPLETED) {
            val errorMsg = jobExecution.allFailureExceptions
                .joinToString("; ") { it.message ?: "Unknown error" }
                .takeIf { it.isNotEmpty() }
            if (errorMsg != null) {
                emailAccountService.recordError(accountId, errorMsg)
            }
            return
        }

        // Extract highest UID per folder from header sync step execution contexts
        var inboxUid: Long? = null
        var sentUid: Long? = null

        for (stepExecution in jobExecution.stepExecutions) {
            val stepName = stepExecution.stepName
            val uid = stepExecution.executionContext.getLong("highestUid", 0L)
            if (uid <= 0) continue

            when {
                stepName.contains("_INBOX") -> inboxUid = uid
                stepName.contains("_Sent") -> sentUid = uid
            }
        }

        try {
            emailAccountService.updateQuickSyncState(accountId, inboxUid, sentUid)
            emailAccountService.clearError(accountId)
            log.info("Updated account sync state: accountId={}, inboxUid={}, sentUid={}",
                accountId, inboxUid, sentUid)
        } catch (e: Exception) {
            log.error("Failed to update account sync state for accountId={}: {}", accountId, e.message, e)
        }
    }
}
