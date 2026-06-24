package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import com.oconeco.spring_search_tempo.base.domain.CrawlRunMetrics
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorOutcome
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOutcomeStatus
import com.oconeco.spring_search_tempo.base.repos.CrawlRunMetricsRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.StepExecution
import org.springframework.batch.item.ExecutionContext
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * Unit tests for [CrawlMetricsRecorder] (issue #149).
 *
 * Exercises the "translate a finished JobExecution + stopped sampler
 * into one CrawlRunMetrics row" path with stub Spring Batch objects
 * (StepExecution.executionContext is a real ExecutionContext — Spring's
 * stub is happy to be built freestanding) and a mock repository that
 * captures the saved row. The DB-aggregation branch (sumBytesForJobRun,
 * countFilesByLevelForJobRun) is exercised by stubbing those methods on
 * the repo mock.
 */
@DisplayName("CrawlMetricsRecorder (issue #149)")
class CrawlMetricsRecorderTest {

    private lateinit var repo: CrawlRunMetricsRepository
    private lateinit var recorder: CrawlMetricsRecorder
    private lateinit var collector: CrawlMetricsCollector

    @BeforeEach
    fun setUp() {
        repo = mock(CrawlRunMetricsRepository::class.java)
        recorder = CrawlMetricsRecorder(repo)
        // Idle collector — won't sample anything real because we use a
        // mock DataSource and stop immediately, but `peakHeapBytes` will
        // get a non-zero value from the immediate sample on start().
        collector = CrawlMetricsCollector(
            dataSource = mock(DataSource::class.java),
            sampleIntervalSeconds = 9999L
        )
        `when`(repo.save(org.mockito.ArgumentMatchers.any(CrawlRunMetrics::class.java)))
            .thenAnswer { invocation -> invocation.getArgument(0) }
    }

    @Test
    @DisplayName("aggregates step-execution counters into the metrics row")
    fun aggregatesStepCounters() {
        collector.start()
        collector.stop()

        val execution = stubJobExecution(
            executionId = 99L,
            jobRunId = 1234L,
            steps = listOf(
                stepContext(filesDiscovered = 10, filesNew = 6, filesUpdated = 2, filesSkipped = 1, filesError = 1),
                stepContext(filesDiscovered = 5, filesNew = 3, filesUpdated = 0, filesSkipped = 0, filesError = 0)
            )
        )
        `when`(repo.sumBytesForJobRun(1234L)).thenReturn(987_654L)
        `when`(repo.countFilesByLevelForJobRun(1234L)).thenReturn(
            listOf(
                arrayOf<Any?>(AnalysisStatus.INDEX, 7L),
                arrayOf<Any?>(AnalysisStatus.LOCATE, 3L),
                arrayOf<Any?>(AnalysisStatus.SKIP, 5L)
            )
        )

        val now = OffsetDateTime.now()
        val metrics = recorder.record(
            config = stubConfig(),
            outcome = stubOutcome(),
            execution = execution,
            collector = collector,
            startedAt = now.minusSeconds(5),
            finishedAt = now
        )

        // Step-context sums
        assertThat(metrics.filesVisited).isEqualTo(15L)
        assertThat(metrics.filesIndexed).isEqualTo(11L) // 6+2+3+0
        assertThat(metrics.filesSkipped).isEqualTo(1L)
        assertThat(metrics.tikaFailures).isEqualTo(1L)

        // DB-sourced aggregates
        assertThat(metrics.bytesRead).isEqualTo(987_654L)
        assertThat(metrics.filesLevelIndex).isEqualTo(7L)
        assertThat(metrics.filesLevelLocate).isEqualTo(3L)
        assertThat(metrics.filesLevelSkip).isEqualTo(5L)
        assertThat(metrics.filesLevelAnalyze).isEqualTo(0L)

        // Linkage
        assertThat(metrics.jobExecutionId).isEqualTo("99")
        assertThat(metrics.jobRunId).isEqualTo(1234L)
        assertThat(metrics.crawlConfigId).isEqualTo(42L)
        assertThat(metrics.runStatus).isEqualTo("COMPLETED")
        assertThat(metrics.peakHeapBytes).isPositive
        assertThat(metrics.durationMs).isBetween(0L, 60_000L)
    }

    @Test
    @DisplayName("no JobExecution → records minimal row with outcome-derived status")
    fun nullExecutionRecordsMinimalRow() {
        collector.start()
        collector.stop()

        val outcome = stubOutcome().apply {
            outcome = FsCrawlOutcomeStatus.FAILED
            errorMessage = "no config bound"
        }
        val now = OffsetDateTime.now()
        val metrics = recorder.record(
            config = stubConfig(),
            outcome = outcome,
            execution = null,
            collector = collector,
            startedAt = now.minusSeconds(1),
            finishedAt = now
        )

        assertThat(metrics.runStatus).isEqualTo("FAILED")
        assertThat(metrics.errorMessage).isEqualTo("no config bound")
        assertThat(metrics.bytesRead).isZero
        assertThat(metrics.filesVisited).isZero
        assertThat(metrics.jobRunId).isNull()
    }

    @Test
    @DisplayName("DB aggregation failure is swallowed — metrics row still persists")
    fun dbAggregationFailureIsSwallowed() {
        collector.start()
        collector.stop()

        val execution = stubJobExecution(
            executionId = 1L,
            jobRunId = 50L,
            steps = listOf(stepContext(filesDiscovered = 1))
        )
        `when`(repo.sumBytesForJobRun(50L)).thenThrow(RuntimeException("postgres is on fire"))

        val now = OffsetDateTime.now()
        val metrics = recorder.record(
            config = stubConfig(),
            outcome = stubOutcome(),
            execution = execution,
            collector = collector,
            startedAt = now,
            finishedAt = now
        )

        assertThat(metrics.filesVisited).isEqualTo(1L) // step-context path still applied
        assertThat(metrics.bytesRead).isZero          // DB path bailed safely
    }

    private fun stubConfig() = CrawlConfig().apply {
        id = 42L
        name = "test-config"
    }

    private fun stubOutcome() = FsCrawlOrchestratorOutcome().apply {
        id = 7L
        outcome = FsCrawlOutcomeStatus.SUCCEEDED
        startedAt = OffsetDateTime.now()
    }

    private fun stepContext(
        filesDiscovered: Long = 0,
        filesNew: Long = 0,
        filesUpdated: Long = 0,
        filesSkipped: Long = 0,
        filesError: Long = 0
    ): ExecutionContext = ExecutionContext().apply {
        putLong("filesDiscovered", filesDiscovered)
        putLong("filesNew", filesNew)
        putLong("filesUpdated", filesUpdated)
        putLong("filesSkipped", filesSkipped)
        putLong("filesError", filesError)
    }

    private fun stubJobExecution(
        executionId: Long,
        jobRunId: Long?,
        steps: List<ExecutionContext>
    ): JobExecution {
        val execution = mock(JobExecution::class.java)
        `when`(execution.id).thenReturn(executionId)
        `when`(execution.status).thenReturn(BatchStatus.COMPLETED)

        val jobContext = ExecutionContext()
        if (jobRunId != null) {
            jobContext.putLong(JobRunTrackingListener.JOB_RUN_ID_KEY, jobRunId)
        }
        `when`(execution.executionContext).thenReturn(jobContext)

        val stepExecs = steps.map { ctx ->
            val se = mock(StepExecution::class.java)
            `when`(se.executionContext).thenReturn(ctx)
            se
        }
        `when`(execution.stepExecutions).thenReturn(stepExecs)
        return execution
    }
}
