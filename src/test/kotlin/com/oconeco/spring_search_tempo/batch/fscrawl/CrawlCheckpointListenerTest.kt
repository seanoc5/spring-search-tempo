package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.CrawlCheckpoint
import com.oconeco.spring_search_tempo.base.service.CrawlCheckpointService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder

class CrawlCheckpointListenerTest {

    private lateinit var checkpointService: CrawlCheckpointService
    private lateinit var listener: CrawlCheckpointListener

    @BeforeEach
    fun setUp() {
        checkpointService = mock(CrawlCheckpointService::class.java)
        listener = CrawlCheckpointListener(checkpointService)
    }

    @Test
    fun `beforeJob copies checkpoint URI into job execution context`() {
        val checkpoint = CrawlCheckpoint().apply {
            crawlConfigId = 42L
            lastProcessedUri = "/tmp/foo/bar"
        }
        `when`(checkpointService.find(42L)).thenReturn(checkpoint)

        val jobExecution = newJobExecution(crawlConfigId = "42")
        listener.beforeJob(jobExecution)

        assertEquals(
            "/tmp/foo/bar",
            jobExecution.executionContext.getString(CombinedCrawlReader.RESUME_FROM_URI_KEY)
        )
    }

    @Test
    fun `beforeJob does nothing when no checkpoint exists`() {
        `when`(checkpointService.find(42L)).thenReturn(null)

        val jobExecution = newJobExecution(crawlConfigId = "42")
        listener.beforeJob(jobExecution)

        assertFalse(jobExecution.executionContext.containsKey(CombinedCrawlReader.RESUME_FROM_URI_KEY))
    }

    @Test
    fun `beforeJob with forceFullRecrawl clears existing checkpoint and skips resume`() {
        val jobExecution = newJobExecution(
            crawlConfigId = "42",
            extra = mapOf(CrawlCheckpointListener.FORCE_FULL_RECRAWL_KEY to "true")
        )
        listener.beforeJob(jobExecution)

        verify(checkpointService).clear(42L)
        verify(checkpointService, never()).find(42L)
        assertNull(jobExecution.executionContext.get(CombinedCrawlReader.RESUME_FROM_URI_KEY))
    }

    @Test
    fun `afterJob clears checkpoint only on COMPLETED`() {
        val completed = newJobExecution(crawlConfigId = "42").apply { status = BatchStatus.COMPLETED }
        listener.afterJob(completed)
        verify(checkpointService).clear(42L)
    }

    @Test
    fun `afterJob preserves checkpoint when job FAILED`() {
        val failed = newJobExecution(crawlConfigId = "42").apply { status = BatchStatus.FAILED }
        listener.afterJob(failed)
        verify(checkpointService, never()).clear(42L)
    }

    @Test
    fun `afterJob ignores job without crawlConfigId parameter`() {
        val anon = newJobExecution(crawlConfigId = null).apply { status = BatchStatus.COMPLETED }
        listener.afterJob(anon)
        verify(checkpointService, never()).clear(org.mockito.ArgumentMatchers.anyLong())
    }

    private fun newJobExecution(
        crawlConfigId: String?,
        extra: Map<String, String> = emptyMap()
    ): JobExecution {
        val builder = JobParametersBuilder()
        if (crawlConfigId != null) {
            builder.addString(JobRunTrackingListener.CRAWL_CONFIG_ID_KEY, crawlConfigId)
        }
        extra.forEach { (k, v) -> builder.addString(k, v) }
        val params: JobParameters = builder.toJobParameters()
        return JobExecution(JobInstance(1L, "fsCrawlJob"), params)
    }
}
