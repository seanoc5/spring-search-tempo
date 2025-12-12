package com.oconeco.spring_search_tempo.batch.nlp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.StepExecution

/**
 * Test for NLPAutoTriggerListener.
 *
 * Verifies that:
 * - NLP job is triggered after successful fsCrawlJob completion
 * - NLP job is NOT triggered for other jobs
 * - NLP job is NOT triggered on failed jobs
 * - NLP job is NOT triggered when no chunks were created
 * - Auto-trigger can be disabled via configuration
 */
class NLPAutoTriggerListenerTest {

    private lateinit var nlpJobLauncher: NLPJobLauncher
    private lateinit var listener: NLPAutoTriggerListener

    @BeforeEach
    fun setup() {
        nlpJobLauncher = mock(NLPJobLauncher::class.java)
    }

    private fun createJobExecution(
        jobName: String,
        status: BatchStatus,
        stepName: String = "fsCrawlChunks_test",
        writeCount: Long = 10
    ): JobExecution {
        val jobInstance = JobInstance(1L, jobName)
        val jobExecution = JobExecution(jobInstance, JobParameters())
        jobExecution.status = status

        // Add step execution with chunk writes
        val stepExecution = StepExecution(stepName, jobExecution)
        stepExecution.writeCount = writeCount
        jobExecution.addStepExecutions(listOf(stepExecution))

        return jobExecution
    }

    @Test
    fun `should trigger NLP job after successful fsCrawlJob`() {
        // Given
        listener = NLPAutoTriggerListener(nlpJobLauncher, autoTriggerEnabled = true)
        val jobExecution = createJobExecution("fsCrawlJob", BatchStatus.COMPLETED)

        val nlpJobInstance = JobInstance(2L, "nlpProcessingJob")
        val nlpExecution = JobExecution(nlpJobInstance, JobParameters())
        nlpExecution.id = 123L
        `when`(nlpJobLauncher.launchNLPJob("fsCrawlJob")).thenReturn(nlpExecution)

        // When
        listener.afterJob(jobExecution)

        // Then
        verify(nlpJobLauncher).launchNLPJob("fsCrawlJob")
    }

    @Test
    fun `should NOT trigger NLP job for non-fsCrawlJob`() {
        // Given
        listener = NLPAutoTriggerListener(nlpJobLauncher, autoTriggerEnabled = true)
        val jobExecution = createJobExecution("otherJob", BatchStatus.COMPLETED)

        // When
        listener.afterJob(jobExecution)

        // Then
        verify(nlpJobLauncher, never()).launchNLPJob(anyString())
    }

    @Test
    fun `should NOT trigger NLP job when fsCrawlJob fails`() {
        // Given
        listener = NLPAutoTriggerListener(nlpJobLauncher, autoTriggerEnabled = true)
        val jobExecution = createJobExecution("fsCrawlJob", BatchStatus.FAILED)

        // When
        listener.afterJob(jobExecution)

        // Then
        verify(nlpJobLauncher, never()).launchNLPJob(anyString())
    }

    @Test
    fun `should NOT trigger NLP job when fsCrawlJob is stopped`() {
        // Given
        listener = NLPAutoTriggerListener(nlpJobLauncher, autoTriggerEnabled = true)
        val jobExecution = createJobExecution("fsCrawlJob", BatchStatus.STOPPED)

        // When
        listener.afterJob(jobExecution)

        // Then
        verify(nlpJobLauncher, never()).launchNLPJob(anyString())
    }

    @Test
    fun `should NOT trigger NLP job when no chunks were created`() {
        // Given
        listener = NLPAutoTriggerListener(nlpJobLauncher, autoTriggerEnabled = true)
        val jobExecution = createJobExecution(
            "fsCrawlJob",
            BatchStatus.COMPLETED,
            stepName = "fsCrawlChunks_test",
            writeCount = 0
        )

        // When
        listener.afterJob(jobExecution)

        // Then
        verify(nlpJobLauncher, never()).launchNLPJob(anyString())
    }

    @Test
    fun `should NOT trigger NLP job when auto-trigger is disabled`() {
        // Given
        listener = NLPAutoTriggerListener(nlpJobLauncher, autoTriggerEnabled = false)
        val jobExecution = createJobExecution("fsCrawlJob", BatchStatus.COMPLETED)

        // When
        listener.afterJob(jobExecution)

        // Then
        verify(nlpJobLauncher, never()).launchNLPJob(anyString())
    }

    @Test
    fun `should handle NLP job launch failure gracefully`() {
        // Given
        listener = NLPAutoTriggerListener(nlpJobLauncher, autoTriggerEnabled = true)
        val jobExecution = createJobExecution("fsCrawlJob", BatchStatus.COMPLETED)

        `when`(nlpJobLauncher.launchNLPJob("fsCrawlJob"))
            .thenThrow(RuntimeException("NLP job failed to start"))

        // When - should not throw
        assertDoesNotThrow {
            listener.afterJob(jobExecution)
        }

        // Then - NLP failure should not affect crawl job completion
        verify(nlpJobLauncher).launchNLPJob("fsCrawlJob")
    }

    @Test
    fun `beforeJob should do nothing`() {
        // Given
        listener = NLPAutoTriggerListener(nlpJobLauncher, autoTriggerEnabled = true)
        val jobExecution = createJobExecution("fsCrawlJob", BatchStatus.STARTING)

        // When
        listener.beforeJob(jobExecution)

        // Then - no interactions
        verifyNoInteractions(nlpJobLauncher)
    }
}
