package com.oconeco.spring_search_tempo.batch.scheduling

import com.oconeco.spring_search_tempo.base.config.EmailConfiguration
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.stereotype.Component

/**
 * Job execution listener that automatically triggers email sync
 * after a file system crawl job completes successfully.
 *
 * This enables "crawl everything" orchestration - files + emails in sequence.
 *
 * Enabled via `app.scheduling.email.trigger-after-crawl=true`.
 */
@Component
class EmailAutoTriggerListener(
    private val dailyEmailScheduler: DailyEmailScheduler,
    private val emailConfiguration: EmailConfiguration,
    private val schedulingProperties: EmailSchedulingProperties
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(EmailAutoTriggerListener::class.java)
        private const val CRAWL_JOB_NAME = "fsCrawlJob"
    }

    override fun beforeJob(jobExecution: JobExecution) {
        // No action needed before job
    }

    override fun afterJob(jobExecution: JobExecution) {
        val jobName = jobExecution.jobInstance.jobName

        // Only trigger for file crawl jobs
        if (!jobName.startsWith(CRAWL_JOB_NAME)) {
            return
        }

        // Check if trigger-after-crawl is enabled
        if (!schedulingProperties.triggerAfterCrawl) {
            log.debug("Email trigger-after-crawl is disabled. Skipping email sync after {}", jobName)
            return
        }

        // Check if email crawling is enabled
        if (!emailConfiguration.enabled) {
            log.debug("Email crawling is disabled in configuration. Skipping email sync after {}", jobName)
            return
        }

        // Only trigger on successful completion
        if (jobExecution.status != BatchStatus.COMPLETED) {
            log.info("Job {} did not complete successfully (status={}). Skipping email sync.",
                jobName, jobExecution.status)
            return
        }

        // Check if any files were processed
        val filesProcessed = jobExecution.stepExecutions
            .filter { it.stepName.contains("File", ignoreCase = true) || it.stepName.contains("Crawl", ignoreCase = true) }
            .sumOf { it.writeCount }

        if (filesProcessed == 0L) {
            log.debug("No files processed by {}. Skipping email sync.", jobName)
            return
        }

        log.info("Job {} completed successfully with {} items. Triggering email sync.",
            jobName, filesProcessed)

        try {
            dailyEmailScheduler.triggerEmailSync("post-crawl")
        } catch (e: Exception) {
            // Log error but don't fail - email sync is supplementary
            log.error("Failed to trigger email sync after {}: {}", jobName, e.message, e)
        }
    }
}
