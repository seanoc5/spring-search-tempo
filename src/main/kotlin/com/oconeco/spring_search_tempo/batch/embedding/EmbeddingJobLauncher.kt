package com.oconeco.spring_search_tempo.batch.embedding

import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.stereotype.Service

/**
 * Service for launching the standalone embedding processing job.
 * Provides methods for manual triggering via REST API.
 */
@Service
class EmbeddingJobLauncher(
    private val jobLauncher: JobLauncher,
    private val embeddingProcessingJob: Job
) {
    companion object {
        private val log = LoggerFactory.getLogger(EmbeddingJobLauncher::class.java)
    }

    /**
     * Launch the embedding processing job.
     *
     * @param triggeredBy Description of what triggered this job (e.g., "api", "manual")
     * @return The job execution result
     */
    fun launchEmbeddingJob(triggeredBy: String = "manual"): JobExecution {
        log.info("Launching embedding processing job (triggered by: {})", triggeredBy)

        val params = JobParametersBuilder()
            .addLong("timestamp", System.currentTimeMillis())
            .addString("triggeredBy", triggeredBy)
            .toJobParameters()

        return try {
            val execution = jobLauncher.run(embeddingProcessingJob, params)
            log.info("Embedding processing job launched: executionId={}, status={}",
                execution.id, execution.status)
            execution
        } catch (e: Exception) {
            log.error("Failed to launch embedding processing job", e)
            throw e
        }
    }
}
