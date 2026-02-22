package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ExecutionContext

/**
 * Step execution listener that tracks crawl statistics.
 * Makes jobRunId available to readers/processors/writers through step execution context.
 */
class CrawlStepListener : StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(CrawlStepListener::class.java)
        const val JOB_RUN_ID_KEY = "jobRunId"
    }

    override fun beforeStep(stepExecution: StepExecution) {
        // Copy jobRunId from job execution context to step execution context
        val jobRunId = stepExecution.jobExecution.executionContext.getLong(
            JobRunTrackingListener.JOB_RUN_ID_KEY, -1L
        )
        if (jobRunId > 0) {
            stepExecution.executionContext.putLong(JOB_RUN_ID_KEY, jobRunId)
            log.info("Step {} starting with jobRunId: {}", stepExecution.stepName, jobRunId)
        }

        // Initialize statistics counters
        stepExecution.executionContext.putLong("filesDiscovered", 0L)
        stepExecution.executionContext.putLong("filesNew", 0L)
        stepExecution.executionContext.putLong("filesUpdated", 0L)
        stepExecution.executionContext.putLong("filesSkipped", 0L)
        stepExecution.executionContext.putLong("filesError", 0L)
        stepExecution.executionContext.putLong("filesAccessDenied", 0L)
        stepExecution.executionContext.putLong("foldersDiscovered", 0L)
        stepExecution.executionContext.putLong("foldersNew", 0L)
        stepExecution.executionContext.putLong("foldersUpdated", 0L)
        stepExecution.executionContext.putLong("foldersSkipped", 0L)
    }

    override fun afterStep(stepExecution: StepExecution): ExitStatus? {
        val context = stepExecution.executionContext
        log.info(
            "Step {} completed - Files: {} discovered ({} new, {} updated, {} skipped), " +
                    "Folders: {} discovered ({} new, {} updated, {} skipped)",
            stepExecution.stepName,
            context.getLong("filesDiscovered", 0),
            context.getLong("filesNew", 0),
            context.getLong("filesUpdated", 0),
            context.getLong("filesSkipped", 0),
            context.getLong("foldersDiscovered", 0),
            context.getLong("foldersNew", 0),
            context.getLong("foldersUpdated", 0),
            context.getLong("foldersSkipped", 0)
        )
        return null
    }

    /**
     * Increment file counter based on status.
     */
    fun incrementFileCounter(
        context: ExecutionContext,
        isNew: Boolean,
        analysisStatus: AnalysisStatus?
    ) {
        context.putLong("filesDiscovered", context.getLong("filesDiscovered", 0) + 1)

        if (analysisStatus == AnalysisStatus.SKIP) {
            context.putLong("filesSkipped", context.getLong("filesSkipped", 0) + 1)
        } else if (isNew) {
            context.putLong("filesNew", context.getLong("filesNew", 0) + 1)
        } else {
            context.putLong("filesUpdated", context.getLong("filesUpdated", 0) + 1)
        }
    }

    /**
     * Increment folder counter based on status.
     */
    fun incrementFolderCounter(
        context: ExecutionContext,
        isNew: Boolean,
        analysisStatus: AnalysisStatus?
    ) {
        context.putLong("foldersDiscovered", context.getLong("foldersDiscovered", 0) + 1)

        if (analysisStatus == AnalysisStatus.SKIP) {
            context.putLong("foldersSkipped", context.getLong("foldersSkipped", 0) + 1)
        } else if (isNew) {
            context.putLong("foldersNew", context.getLong("foldersNew", 0) + 1)
        } else {
            context.putLong("foldersUpdated", context.getLong("foldersUpdated", 0) + 1)
        }
    }
}
