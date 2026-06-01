package com.oconeco.spring_search_tempo.batch.historycrawl

import com.oconeco.spring_search_tempo.base.BrowserProfileService
import com.oconeco.spring_search_tempo.base.config.BrowserHistoryConfiguration
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.stereotype.Component


/**
 * Chains a history import after each successful bookmark import.
 *
 * Disable via `app.browser.history.enabled=false` or
 * `app.browser.history.auto-trigger=false`.
 */
@Component
class HistoryAutoTriggerListener(
    private val historyImportJobBuilder: HistoryImportJobBuilder,
    private val jobLauncher: JobLauncher,
    private val browserProfileService: BrowserProfileService,
    private val browserHistoryConfiguration: BrowserHistoryConfiguration
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(HistoryAutoTriggerListener::class.java)
        private const val BOOKMARK_JOB_PREFIX = "bookmarkImportJob_"
    }

    override fun beforeJob(jobExecution: JobExecution) {
        // no-op
    }

    override fun afterJob(jobExecution: JobExecution) {
        val jobName = jobExecution.jobInstance.jobName
        if (!jobName.startsWith(BOOKMARK_JOB_PREFIX)) return

        if (!browserHistoryConfiguration.enabled || !browserHistoryConfiguration.autoTrigger) {
            log.debug("History auto-trigger disabled (enabled={}, autoTrigger={})",
                browserHistoryConfiguration.enabled, browserHistoryConfiguration.autoTrigger)
            return
        }
        if (jobExecution.status != BatchStatus.COMPLETED) {
            log.info("Bookmark job {} did not complete (status={}); skipping history sync",
                jobName, jobExecution.status)
            return
        }

        val profileId = jobName.removePrefix(BOOKMARK_JOB_PREFIX).toLongOrNull() ?: run {
            log.warn("Could not extract profile id from job name: {}", jobName)
            return
        }

        try {
            val profile = browserProfileService.get(profileId)
            val job = historyImportJobBuilder.buildJob(profile)
            val params = JobParametersBuilder()
                .addString("profileId", profileId.toString())
                .addLong("timestamp", System.currentTimeMillis())
                .addString("triggeredBy", jobName)
                .toJobParameters()
            val execution = jobLauncher.run(job, params)
            log.info("History import triggered after {}: executionId={}", jobName, execution.id)
        } catch (e: Exception) {
            log.error("Failed to chain history import after {}: {}", jobName, e.message, e)
        }
    }

}
