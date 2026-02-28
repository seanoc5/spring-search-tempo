package com.oconeco.spring_search_tempo.batch

import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.batch.fscrawl.JobRunTrackingListener
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ItemWriteListener
import org.springframework.batch.item.Chunk
import org.springframework.stereotype.Component

/**
 * Item write listener that updates progress tracking after each chunk is written.
 *
 * Increments processedCount by the number of items written, enabling
 * real-time "Processed X of Y" display in the UI.
 *
 * Note: This listener tracks write count. For jobs where items are filtered,
 * you may want to use an ItemReadListener instead to track all reads.
 */
@Component
class ProgressTrackingItemWriteListener<T>(
    private val jobRunService: JobRunService
) : ItemWriteListener<T> {

    companion object {
        private val log = LoggerFactory.getLogger(ProgressTrackingItemWriteListener::class.java)
    }

    override fun beforeWrite(items: Chunk<out T>) {
        // No action needed before write
    }

    override fun afterWrite(items: Chunk<out T>) {
        // Get jobRunId from the current step execution context
        // This is set by JobRunTrackingListener or EmailJobRunTrackingListener
        val jobRunId = getJobRunIdFromContext()
        if (jobRunId != null && items.size() > 0) {
            try {
                jobRunService.incrementProcessed(jobRunId, items.size())
                log.trace("Incremented progress by {} for jobRunId={}", items.size(), jobRunId)
            } catch (e: Exception) {
                // Don't fail the job if progress update fails
                log.warn("Failed to update progress for jobRunId={}: {}", jobRunId, e.message)
            }
        }
    }

    override fun onWriteError(exception: Exception, items: Chunk<out T>) {
        // Could track error counts here if needed
    }

    /**
     * Get jobRunId from the thread-local StepContext.
     * Works with both JobRunTrackingListener and EmailJobRunTrackingListener.
     */
    private fun getJobRunIdFromContext(): Long? {
        return try {
            val stepContext = org.springframework.batch.core.scope.context.StepSynchronizationManager.getContext()
            val stepExecution = stepContext?.stepExecution ?: return null

            // Try step execution context first
            var jobRunId = stepExecution.executionContext.getLong(JobRunTrackingListener.JOB_RUN_ID_KEY, -1L)
            if (jobRunId > 0) return jobRunId

            // Fall back to job execution context
            jobRunId = stepExecution.jobExecution.executionContext.getLong(JobRunTrackingListener.JOB_RUN_ID_KEY, -1L)
            if (jobRunId > 0) jobRunId else null
        } catch (e: Exception) {
            log.trace("Could not get jobRunId from context: {}", e.message)
            null
        }
    }
}
