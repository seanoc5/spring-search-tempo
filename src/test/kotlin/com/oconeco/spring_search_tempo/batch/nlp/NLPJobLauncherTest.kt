package com.oconeco.spring_search_tempo.batch.nlp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.launch.JobLauncher

/**
 * Test for NLPJobLauncher.
 *
 * Verifies that:
 * - NLP job is launched with correct parameters
 * - triggeredBy parameter is passed correctly
 * - Exceptions are propagated properly
 */
class NLPJobLauncherTest {

    private lateinit var jobLauncher: JobLauncher
    private lateinit var nlpProcessingJob: Job
    private lateinit var nlpJobLauncher: NLPJobLauncher

    @BeforeEach
    fun setup() {
        jobLauncher = mock(JobLauncher::class.java)
        nlpProcessingJob = mock(Job::class.java)
        nlpJobLauncher = NLPJobLauncher(jobLauncher, nlpProcessingJob)
    }

    @Test
    fun `should launch NLP job successfully`() {
        // Given
        val jobInstance = JobInstance(1L, "nlpProcessingJob")
        val jobExecution = JobExecution(jobInstance, JobParameters())
        jobExecution.status = BatchStatus.COMPLETED

        `when`(jobLauncher.run(any(Job::class.java), any(JobParameters::class.java))).thenReturn(jobExecution)

        // When
        val result = nlpJobLauncher.launchNLPJob("test")

        // Then
        assertNotNull(result)
        assertEquals(BatchStatus.COMPLETED, result.status)
        verify(jobLauncher).run(any(Job::class.java), any(JobParameters::class.java))
    }

    @Test
    fun `should pass triggeredBy parameter`() {
        // Given
        val jobInstance = JobInstance(1L, "nlpProcessingJob")
        val jobExecution = JobExecution(jobInstance, JobParameters())
        jobExecution.status = BatchStatus.STARTED

        val paramsCaptor = ArgumentCaptor.forClass(JobParameters::class.java)
        `when`(jobLauncher.run(any(Job::class.java), any(JobParameters::class.java))).thenReturn(jobExecution)

        // When
        val result = nlpJobLauncher.launchNLPJob("fsCrawlJob")

        // Then
        assertNotNull(result)
        verify(jobLauncher).run(any(Job::class.java), paramsCaptor.capture())
        assertEquals("fsCrawlJob", paramsCaptor.value.getString("triggeredBy"))
    }

    @Test
    fun `should use default triggeredBy when not specified`() {
        // Given
        val jobInstance = JobInstance(1L, "nlpProcessingJob")
        val jobExecution = JobExecution(jobInstance, JobParameters())

        val paramsCaptor = ArgumentCaptor.forClass(JobParameters::class.java)
        `when`(jobLauncher.run(any(Job::class.java), any(JobParameters::class.java))).thenReturn(jobExecution)

        // When
        val result = nlpJobLauncher.launchNLPJob()

        // Then
        assertNotNull(result)
        verify(jobLauncher).run(any(Job::class.java), paramsCaptor.capture())
        assertEquals("manual", paramsCaptor.value.getString("triggeredBy"))
    }

    @Test
    fun `should propagate exceptions from job launcher`() {
        // Given
        `when`(jobLauncher.run(any(Job::class.java), any(JobParameters::class.java)))
            .thenThrow(RuntimeException("Job launch failed"))

        // When/Then
        assertThrows(RuntimeException::class.java) {
            nlpJobLauncher.launchNLPJob("test")
        }
    }

    @Test
    fun `should include timestamp parameter for unique job runs`() {
        // Given
        val jobInstance = JobInstance(1L, "nlpProcessingJob")
        val jobExecution = JobExecution(jobInstance, JobParameters())

        val paramsCaptor = ArgumentCaptor.forClass(JobParameters::class.java)
        `when`(jobLauncher.run(any(Job::class.java), any(JobParameters::class.java))).thenReturn(jobExecution)

        // When
        val result = nlpJobLauncher.launchNLPJob("test")

        // Then
        assertNotNull(result)
        verify(jobLauncher).run(any(Job::class.java), paramsCaptor.capture())
        val timestamp = paramsCaptor.value.getLong("timestamp")
        assertNotNull(timestamp)
        assertTrue(timestamp!! > 0)
    }
}
