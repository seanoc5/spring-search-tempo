package com.oconeco.spring_search_tempo.batch.nlp

import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.stereotype.Service

/**
 * Service for launching the NLP processing job.
 * Provides methods for both automatic triggering (after crawl) and manual triggering (via REST).
 */
@Service
class NLPJobLauncher(
    private val jobLauncher: JobLauncher,
    private val nlpProcessingJob: Job
) {
    companion object {
        private val log = LoggerFactory.getLogger(NLPJobLauncher::class.java)
    }

    /**
     * Launch the NLP processing job.
     *
     * @param triggeredBy Description of what triggered this job (e.g., "fsCrawlJob", "manual", "api")
     * @return The job execution result
     */
    fun launchNLPJob(triggeredBy: String = "manual"): JobExecution {
        log.info("Launching NLP processing job (triggered by: {})", triggeredBy)

        val params = JobParametersBuilder()
            .addLong("timestamp", System.currentTimeMillis())
            .addString("triggeredBy", triggeredBy)
            .toJobParameters()

        return try {
            val execution = jobLauncher.run(nlpProcessingJob, params)
            log.info("NLP processing job launched: executionId={}, status={}",
                execution.id, execution.status)
            execution
        } catch (e: Exception) {
            log.error("Failed to launch NLP processing job", e)
            throw e
        }
    }

    /**
     * Launch the NLP processing job asynchronously.
     * Returns immediately after starting the job.
     *
     * @param triggeredBy Description of what triggered this job
     * @return The job execution (may still be running)
     */
    fun launchNLPJobAsync(triggeredBy: String = "manual"): JobExecution {
        // The default SimpleJobLauncher runs synchronously, but TaskExecutorJobLauncher
        // would run async. For now, we just launch and return immediately.
        return launchNLPJob(triggeredBy)
    }
}
