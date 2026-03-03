package com.oconeco.spring_search_tempo.batch.embedding

import com.oconeco.spring_search_tempo.base.service.EmbeddingService
import com.oconeco.spring_search_tempo.base.service.GpuStatus
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.stereotype.Component

/**
 * Listener that checks GPU status before embedding jobs and sets context for UI.
 *
 * When GPU is not available, logs a warning and sets a job execution context
 * flag that can be read by the UI to display a persistent banner.
 */
@Component
class EmbeddingGpuStatusListener(
    private val embeddingService: EmbeddingService
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(EmbeddingGpuStatusListener::class.java)

        const val GPU_MODE_KEY = "embeddingGpuMode"
        const val GPU_WARNING_KEY = "embeddingGpuWarning"
        const val GPU_DEVICE_KEY = "embeddingGpuDevice"
    }

    // Cache GPU status to avoid repeated checks
    private var cachedStatus: GpuStatus? = null

    override fun beforeJob(jobExecution: JobExecution) {
        // Only check for jobs that involve embeddings
        val jobName = jobExecution.jobInstance.jobName
        if (!jobName.contains("embedding", ignoreCase = true) &&
            !jobName.contains("nlp", ignoreCase = true) &&
            !jobName.contains("semantic", ignoreCase = true) &&
            !jobName.contains("progressive", ignoreCase = true)
        ) {
            return
        }

        val status = cachedStatus ?: embeddingService.checkGpuStatus().also { cachedStatus = it }

        // Set execution context for UI
        jobExecution.executionContext.putString(GPU_MODE_KEY, status.mode)

        if (!status.gpuAvailable) {
            jobExecution.executionContext.putString(
                GPU_WARNING_KEY,
                status.warning ?: "GPU not available - embeddings will be slow"
            )
            log.warn("Starting embedding job in CPU mode - this will be significantly slower than GPU mode")
        } else {
            status.gpuDevice?.let {
                jobExecution.executionContext.putString(GPU_DEVICE_KEY, it)
            }
            log.info("Starting embedding job with GPU acceleration: {}", status.gpuDevice ?: "available")
        }
    }

    override fun afterJob(jobExecution: JobExecution) {
        // Log final GPU mode used
        val mode = jobExecution.executionContext.getString(GPU_MODE_KEY, "unknown")
        log.info("Embedding job completed in {} mode", mode)
    }

    /**
     * Get current GPU status (for monitoring endpoints).
     */
    fun getGpuStatus(): GpuStatus {
        return cachedStatus ?: embeddingService.checkGpuStatus().also { cachedStatus = it }
    }

    /**
     * Force refresh of GPU status.
     */
    fun refreshGpuStatus(): GpuStatus {
        cachedStatus = null
        return embeddingService.checkGpuStatus().also { cachedStatus = it }
    }
}
