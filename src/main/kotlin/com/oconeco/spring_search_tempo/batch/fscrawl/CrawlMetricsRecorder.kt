package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import com.oconeco.spring_search_tempo.base.domain.CrawlRunMetrics
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorOutcome
import com.oconeco.spring_search_tempo.base.repos.CrawlRunMetricsRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Builds and persists a [CrawlRunMetrics] row from a finished
 * [JobExecution] + a stopped [CrawlMetricsCollector] (issue #149).
 *
 * Pulled out of [FsCrawlOrchestrator] so the aggregation logic can be
 * unit-tested without a running orchestrator. The recorder owns the
 * "translate Spring Batch + DB state into one metrics row" responsibility
 * end-to-end; the orchestrator just hands it a populated outcome and
 * the still-running execution wrappers.
 */
@Service
class CrawlMetricsRecorder(
    private val metricsRepository: CrawlRunMetricsRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(CrawlMetricsRecorder::class.java)
    }

    /**
     * Persist a metrics row for one crawl. Safe to call regardless of
     * whether the underlying job succeeded — captures whatever ran.
     *
     * @param config the crawl config that was just run.
     * @param outcome the orchestrator outcome (already saved by caller).
     * @param execution the batch JobExecution returned by the launcher,
     *   or null if the job never launched.
     * @param collector the resource sampler — must already be [CrawlMetricsCollector.stop]ped.
     * @param startedAt wall-clock at orchestrator-side start (used for
     *   the unique `(crawl_config_id, started_at)` key — the JobExecution's
     *   startTime is slightly later and isn't always populated on failure).
     * @param finishedAt wall-clock at orchestrator-side stop.
     */
    @Transactional
    fun record(
        config: CrawlConfig,
        outcome: FsCrawlOrchestratorOutcome,
        execution: JobExecution?,
        collector: CrawlMetricsCollector,
        startedAt: OffsetDateTime,
        finishedAt: OffsetDateTime
    ): CrawlRunMetrics {
        val metrics = CrawlRunMetrics().apply {
            this.crawlConfigId = config.id ?: 0L
            this.crawlConfigName = config.name
            this.orchestratorOutcome = outcome
            this.startedAt = startedAt
            this.finishedAt = finishedAt
            this.durationMs = Duration.between(startedAt, finishedAt).toMillis()
            this.peakHeapBytes = collector.peakHeapBytes.takeIf { it > 0 }
            this.peakHikariActive = collector.peakHikariActive.takeIf { it > 0 }
            this.runStatus = execution?.status?.name
                ?: outcome.outcome.name
            this.errorMessage = outcome.errorMessage
            this.uri = "tempo:crawl-run-metrics:${config.id}:${startedAt.toEpochSecond()}:${System.nanoTime()}"
            this.label = "metrics ${config.name} @ $startedAt"
        }

        execution?.let { exec ->
            metrics.jobExecutionId = exec.id?.toString()
            populateFromStepContext(metrics, exec)
        }

        // DB-sourced aggregates: bytes + per-level counts. Only meaningful
        // when the job actually wrote rows, so we need jobRunId.
        val jobRunId: Long? = execution?.executionContext
            ?.getLong(JobRunTrackingListener.JOB_RUN_ID_KEY, -1L)
            ?.let { if (it > 0L) it else null }
        metrics.jobRunId = jobRunId
        if (jobRunId != null) {
            try {
                metrics.bytesRead = metricsRepository.sumBytesForJobRun(jobRunId)
                applyLevelCounts(metrics, jobRunId)
            } catch (e: Exception) {
                log.warn(
                    "CrawlMetricsRecorder: DB aggregation failed for jobRunId={}: {}",
                    jobRunId, e.message
                )
            }
        }

        val saved = metricsRepository.save(metrics)
        log.info(
            "Recorded crawl metrics id={} config='{}' duration={}ms files={} bytes={} peakHeap={}MB peakPool={} tikaFailures={}",
            saved.id, config.name, saved.durationMs, saved.filesVisited,
            saved.bytesRead, (saved.peakHeapBytes ?: 0) / (1024 * 1024),
            saved.peakHikariActive, saved.tikaFailures
        )
        return saved
    }

    private fun populateFromStepContext(metrics: CrawlRunMetrics, execution: JobExecution) {
        var filesDiscovered = 0L
        var filesNew = 0L
        var filesUpdated = 0L
        var filesSkipped = 0L
        var filesError = 0L
        for (stepExec in execution.stepExecutions) {
            val ctx = stepExec.executionContext
            filesDiscovered += ctx.getLong("filesDiscovered", 0L)
            filesNew += ctx.getLong("filesNew", 0L)
            filesUpdated += ctx.getLong("filesUpdated", 0L)
            filesSkipped += ctx.getLong("filesSkipped", 0L)
            filesError += ctx.getLong("filesError", 0L)
        }
        metrics.filesVisited = filesDiscovered
        metrics.filesIndexed = filesNew + filesUpdated
        metrics.filesSkipped = filesSkipped
        metrics.tikaFailures = filesError

        if (execution.status == BatchStatus.FAILED && metrics.errorMessage == null) {
            metrics.errorMessage = execution.allFailureExceptions
                .firstOrNull()?.message
                ?: execution.exitStatus.exitDescription.ifBlank { null }
        }
    }

    private fun applyLevelCounts(metrics: CrawlRunMetrics, jobRunId: Long) {
        for (row in metricsRepository.countFilesByLevelForJobRun(jobRunId)) {
            val level = row[0] as? AnalysisStatus ?: continue
            val count = (row[1] as? Number)?.toLong() ?: continue
            when (level) {
                AnalysisStatus.SKIP -> metrics.filesLevelSkip = count
                AnalysisStatus.LOCATE -> metrics.filesLevelLocate = count
                AnalysisStatus.INDEX -> metrics.filesLevelIndex = count
                AnalysisStatus.ANALYZE -> metrics.filesLevelAnalyze = count
                AnalysisStatus.SEMANTIC -> Unit // not in scope for this counter set
            }
        }
    }
}
