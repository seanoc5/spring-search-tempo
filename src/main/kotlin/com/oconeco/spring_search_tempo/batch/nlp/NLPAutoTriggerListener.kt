package com.oconeco.spring_search_tempo.batch.nlp

import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Job execution listener that automatically triggers NLP processing
 * after a file system crawl job completes successfully.
 *
 * This listener should be attached to fsCrawlJob to enable automatic
 * NLP processing of newly crawled content.
 *
 * Can be disabled via configuration: app.nlp.auto-trigger=false
 */
@Component
class NLPAutoTriggerListener(
    private val nlpJobLauncher: NLPJobLauncher,
    @Value("\${app.nlp.auto-trigger:true}")
    private val autoTriggerEnabled: Boolean
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(NLPAutoTriggerListener::class.java)
        private val TRIGGER_JOB_PREFIXES = setOf("fsCrawlJob", "emailQuickSync")
    }

    override fun beforeJob(jobExecution: JobExecution) {
        // No action needed before job
    }

    override fun afterJob(jobExecution: JobExecution) {
        val jobName = jobExecution.jobInstance.jobName

        // Only trigger for specific job prefixes
        if (!TRIGGER_JOB_PREFIXES.any { jobName.startsWith(it) }) {
            log.debug("Job {} is not configured to trigger NLP processing", jobName)
            return
        }

        // Check if auto-trigger is enabled
        if (!autoTriggerEnabled) {
            log.info("NLP auto-trigger is disabled. Skipping NLP processing after {}", jobName)
            return
        }

        // Only trigger on successful completion
        if (jobExecution.status != BatchStatus.COMPLETED) {
            log.info("Job {} did not complete successfully (status={}). Skipping NLP processing.",
                jobName, jobExecution.status)
            return
        }

        // Check if any chunks were created (via chunking step)
        val chunksCreated = jobExecution.stepExecutions
            .filter { it.stepName.contains("Chunk", ignoreCase = true) }
            .sumOf { it.writeCount }

        if (chunksCreated == 0L) {
            log.info("No new chunks created by {}. Skipping NLP processing.", jobName)
            return
        }

        log.info("Job {} completed successfully with {} chunks. Triggering NLP processing.",
            jobName, chunksCreated)

        try {
            val nlpExecution = nlpJobLauncher.launchNLPJob(triggeredBy = jobName)
            log.info("NLP processing job triggered: executionId={}", nlpExecution.id)
        } catch (e: Exception) {
            // Log error but don't fail - NLP is supplementary processing
            log.error("Failed to trigger NLP processing after {}: {}", jobName, e.message, e)
        }
    }
}
