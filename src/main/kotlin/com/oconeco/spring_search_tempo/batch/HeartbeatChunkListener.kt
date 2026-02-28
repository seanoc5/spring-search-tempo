package com.oconeco.spring_search_tempo.batch

import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.batch.fscrawl.JobRunTrackingListener
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ChunkListener
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.stereotype.Component

/**
 * Chunk listener that updates the job heartbeat after each chunk is processed.
 *
 * This provides reliable stale job detection - if a job crashes, the heartbeat
 * stops updating, and the job can be detected as stale within 2 minutes
 * (instead of waiting 4+ hours based on start time).
 *
 * The listener updates heartbeat after every chunk to ensure frequent updates
 * without significant overhead (one UPDATE per ~100 items processed).
 */
@Component
class HeartbeatChunkListener(
    private val jobRunService: JobRunService
) : ChunkListener {

    companion object {
        private val log = LoggerFactory.getLogger(HeartbeatChunkListener::class.java)
    }

    override fun beforeChunk(context: ChunkContext) {
        // No action needed before chunk
    }

    override fun afterChunk(context: ChunkContext) {
        val jobRunId = getJobRunId(context)
        if (jobRunId != null) {
            try {
                jobRunService.updateHeartbeat(jobRunId)
                log.trace("Updated heartbeat for jobRunId={}", jobRunId)
            } catch (e: Exception) {
                // Don't fail the job if heartbeat update fails
                log.warn("Failed to update heartbeat for jobRunId={}: {}", jobRunId, e.message)
            }
        }
    }

    override fun afterChunkError(context: ChunkContext) {
        // Still update heartbeat on error - job is still alive
        afterChunk(context)
    }

    /**
     * Extract jobRunId from the chunk context.
     * Looks in both step execution context and job execution context.
     */
    private fun getJobRunId(context: ChunkContext): Long? {
        val stepContext = context.stepContext
        val stepExecution = stepContext.stepExecution

        // First try step execution context
        val stepJobRunId = stepExecution.executionContext.getLong(
            JobRunTrackingListener.JOB_RUN_ID_KEY, -1L
        )
        if (stepJobRunId > 0) {
            return stepJobRunId
        }

        // Fall back to job execution context
        val jobJobRunId = stepExecution.jobExecution.executionContext.getLong(
            JobRunTrackingListener.JOB_RUN_ID_KEY, -1L
        )
        return if (jobJobRunId > 0) jobJobRunId else null
    }
}
