package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorOutcome
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorRun
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorRunStatus
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOutcomeStatus
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.FsCrawlOrchestratorOutcomeRepository
import com.oconeco.spring_search_tempo.base.repos.FsCrawlOrchestratorRunRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.base.service.CrawlConfigMapper
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * "Run all enabled FS crawls" orchestrator (issue #139).
 *
 * Sequential entry point for sweeping every enabled `CrawlConfig` row in
 * one click. The asynchronous-from-the-caller's-perspective handle is
 * [submitAllEnabledCrawls] — it persists an [FsCrawlOrchestratorRun],
 * spins the actual sweep onto a background thread, and returns the run
 * id so operators can poll. [runAllEnabledCrawls] is the synchronous
 * inner loop (also callable from tests / schedulers that already own a
 * worker thread).
 *
 * Parallelism is intentionally out of scope. The companion ticket for
 * concurrent FS crawls is blocked on wall-clock data from this
 * orchestrator's first real runs.
 *
 * Iteration order is **id-ascending** on the `CrawlConfig` table — stable
 * across renames, matches creation order. Documented here so the test
 * IT and the run-summary view can rely on it.
 *
 * Failure semantics: a single crawl failing does NOT abort siblings. Each
 * [FsCrawlOrchestratorOutcome] stands alone, and the [FsCrawlOrchestratorRun]
 * aggregate finishes COMPLETED with `failed > 0` rather than FAILED — the
 * sweep itself succeeded; the failure was per-crawl. The aggregate only
 * goes FAILED if the sweep itself throws (e.g. DB connection lost mid-way).
 */
@Service
class FsCrawlOrchestrator(
    private val crawlConfigRepository: CrawlConfigRepository,
    private val crawlConfigMapper: CrawlConfigMapper,
    private val crawlConfigConverter: CrawlConfigConverter,
    private val fsCrawlJobBuilder: FsCrawlJobBuilder,
    private val orchestratorRunRepository: FsCrawlOrchestratorRunRepository,
    private val orchestratorOutcomeRepository: FsCrawlOrchestratorOutcomeRepository,
    private val jobRepository: JobRepository,
    private val crawlMetricsRecorder: CrawlMetricsRecorder,
    private val dataSource: DataSource
) {
    companion object {
        private val log = LoggerFactory.getLogger(FsCrawlOrchestrator::class.java)
    }

    /**
     * Synchronous launcher used inside [runAllEnabledCrawls] so each
     * per-crawl `run(...)` blocks until the JobExecution reaches a
     * terminal status. The container's primary `jobLauncher` is
     * async-wrapped (see `BatchTaskExecutorConfig.asyncJobLauncherConfigurer`)
     * and would return immediately — which would leave us no way to
     * assemble the aggregate outcome.
     */
    private val syncLauncher: TaskExecutorJobLauncher by lazy {
        TaskExecutorJobLauncher().apply {
            setJobRepository(jobRepository)
            setTaskExecutor(SyncTaskExecutor())
            afterPropertiesSet()
        }
    }

    /**
     * Background executor for [submitAllEnabledCrawls] — one thread per
     * sweep is enough, and SimpleAsyncTaskExecutor matches the rest of
     * the batch wiring's "fire-and-forget" pattern.
     */
    private val sweepExecutor: SimpleAsyncTaskExecutor =
        SimpleAsyncTaskExecutor("fs-orch-sweep-")

    /**
     * Async entry point: persists a fresh [FsCrawlOrchestratorRun] row
     * and immediately returns its id. The actual sweep runs on a
     * background thread; operators poll [findRun] or hit the
     * /admin/crawl/orchestrator-runs page to watch progress.
     *
     * Refuses to start a new sweep if one is already in flight — only
     * one sweep at a time is supported, which matches the "click 'Run
     * All Enabled' once and watch it complete" operator model.
     *
     * @return [SweepHandle] with the new (or in-flight) run id and an
     *   `alreadyRunning` flag so callers can disambiguate.
     */
    fun submitAllEnabledCrawls(triggeredBy: String? = null): SweepHandle {
        val inFlight = orchestratorRunRepository
            .findByRunStatusOrderByStartedAtDesc(FsCrawlOrchestratorRunStatus.RUNNING)
            .firstOrNull()
        if (inFlight != null) {
            log.info(
                "Refusing FS crawl sweep: run id={} already in flight (started {})",
                inFlight.id, inFlight.startedAt
            )
            return SweepHandle(runId = inFlight.id!!, alreadyRunning = true)
        }

        val run = persistInitialRun(triggeredBy)
        val runId = run.id!!

        sweepExecutor.execute {
            try {
                runAllEnabledCrawls(runId)
            } catch (e: Exception) {
                log.error("FS crawl sweep id={} aborted by uncaught exception", runId, e)
                markRunFailed(runId, e.message ?: e.javaClass.simpleName)
            }
        }
        return SweepHandle(runId = runId, alreadyRunning = false)
    }

    /**
     * Synchronous sweep. Iterates enabled crawls in
     * [CrawlConfigRepository.findByEnabledTrueOrderByIdAsc] order,
     * launches each as its own [JobExecution] via the local
     * [syncLauncher], captures per-crawl outcomes, and updates the
     * supplied [runId] row in-place.
     *
     * @param runId id of an already-persisted [FsCrawlOrchestratorRun]
     *   (caller responsible for creating it via [persistInitialRun] —
     *   or call [submitAllEnabledCrawls] which does both).
     * @return the populated [FsCrawlOrchestratorRun] (also reflected in
     *   the database).
     */
    fun runAllEnabledCrawls(runId: Long): FsCrawlOrchestratorRun {
        val enabledCrawls = crawlConfigRepository.findByEnabledTrueOrderByIdAsc()
        log.info("FS crawl sweep id={} starting; {} enabled crawl(s)", runId, enabledCrawls.size)

        setRunTotalCrawls(runId, enabledCrawls.size)

        if (enabledCrawls.isEmpty()) {
            return finishRun(runId, FsCrawlOrchestratorRunStatus.COMPLETED)
        }

        for (config in enabledCrawls) {
            val outcome = runOneCrawl(runId, config)
            log.info(
                "FS crawl sweep id={} crawl '{}' (id={}) finished: {} (elapsed={}ms)",
                runId, outcome.crawlConfigName, outcome.crawlConfigId,
                outcome.outcome, outcome.elapsedMs
            )
        }

        return finishRun(runId, FsCrawlOrchestratorRunStatus.COMPLETED)
    }

    /**
     * Run one crawl synchronously. Captures and persists an
     * [FsCrawlOrchestratorOutcome] row regardless of how the crawl
     * finished — so siblings keep running and the aggregate counters
     * stay honest.
     */
    @Transactional
    fun runOneCrawl(runId: Long, config: CrawlConfig): FsCrawlOrchestratorOutcome {
        val run = orchestratorRunRepository.findById(runId)
            .orElseThrow { IllegalStateException("FsCrawlOrchestratorRun id=$runId not found") }

        val outcome = FsCrawlOrchestratorOutcome().apply {
            this.orchestratorRun = run
            this.crawlConfigId = config.id
            this.crawlConfigName = config.name
            this.crawlConfigLabel = config.label
            this.startedAt = OffsetDateTime.now()
            this.uri = "tempo:fs-orchestrator-outcome:${runId}:${config.id ?: "anon"}:${System.nanoTime()}"
        }

        val started = System.nanoTime()
        val collector = CrawlMetricsCollector(dataSource)
        var execution: JobExecution? = null
        try {
            // Reuse the same DTO → CrawlDefinition path the per-config
            // "Run" button uses so the orchestrator and that button stay
            // in lockstep on pattern/priority mapping.
            val dto = crawlConfigMapper.updateCrawlConfigDTO(config, CrawlConfigDTO())
            val crawlDefinition = crawlConfigConverter.toDefinition(dto)

            val job = fsCrawlJobBuilder.buildJob(
                crawl = crawlDefinition,
                forceFullRecrawl = false,
                crawlConfigId = config.id,
                freshnessHours = config.freshnessHours
            )

            val params = JobParametersBuilder()
                .addString(JobRunTrackingListener.CRAWL_CONFIG_ID_KEY, config.id.toString())
                .addString(CrawlCleanupListener.DELETE_EXISTING_DATA_KEY, "false")
                .addString("orchestratorRunId", runId.toString())
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters()

            collector.start()
            execution = syncLauncher.run(job, params)
            outcome.jobExecutionId = execution.id.toString()
            outcome.batchStatus = execution.status.name
            outcome.outcome = when (execution.status) {
                BatchStatus.COMPLETED -> FsCrawlOutcomeStatus.SUCCEEDED
                else -> FsCrawlOutcomeStatus.FAILED
            }
            if (execution.status.isUnsuccessful) {
                outcome.errorMessage = execution.allFailureExceptions
                    .firstOrNull()?.message
                    ?: execution.exitStatus.exitDescription.ifBlank { null }
            }
        } catch (e: Exception) {
            log.error(
                "FS crawl sweep id={} crawl '{}' (id={}) failed before/while launching job",
                runId, config.name, config.id, e
            )
            outcome.outcome = FsCrawlOutcomeStatus.FAILED
            outcome.errorMessage = e.message ?: e.javaClass.simpleName
        } finally {
            collector.stop()
            outcome.finishedAt = OffsetDateTime.now()
            outcome.elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
        }

        // Persist the outcome directly via its repository rather than
        // navigating `run.crawlOutcomes`. The OneToMany collection on
        // `run` is lazy and isn't reliably initialized inside the
        // sequential sweep loop (each `runOneCrawl` opens its own
        // transaction; the lazy proxy fights with the surrounding
        // sweep). Going through the outcome repo sidesteps that
        // entirely.
        val savedOutcome = orchestratorOutcomeRepository.save(outcome)
        when (savedOutcome.outcome) {
            FsCrawlOutcomeStatus.SUCCEEDED -> run.succeeded += 1
            FsCrawlOutcomeStatus.FAILED -> run.failed += 1
            FsCrawlOutcomeStatus.SKIPPED -> run.skipped += 1
            FsCrawlOutcomeStatus.PENDING -> Unit // shouldn't happen post-loop
        }
        orchestratorRunRepository.save(run)

        // Persist the metrics row for issue #149. We do this even on
        // failure so operators can see *what* the crawl was doing when
        // it died — peak heap and pool sample are the most diagnostic
        // signals when a parallel-crawl attempt OOMs or starves the pool.
        try {
            crawlMetricsRecorder.record(
                config = config,
                outcome = savedOutcome,
                execution = execution,
                collector = collector,
                startedAt = outcome.startedAt ?: OffsetDateTime.now(),
                finishedAt = outcome.finishedAt ?: OffsetDateTime.now()
            )
        } catch (e: Exception) {
            log.warn(
                "CrawlMetricsRecorder failed for config '{}' (id={}): {}",
                config.name, config.id, e.message
            )
        }

        return savedOutcome
    }

    /**
     * Convenience overload — persist a new run row and dispatch the
     * sweep inline. Useful in tests / schedulers that already own a
     * worker thread and don't need the async indirection of
     * [submitAllEnabledCrawls].
     */
    fun runAllEnabledCrawls(triggeredBy: String? = null): FsCrawlOrchestratorRun {
        val run = persistInitialRun(triggeredBy)
        return runAllEnabledCrawls(run.id!!)
    }

    @Transactional
    fun persistInitialRun(triggeredBy: String?): FsCrawlOrchestratorRun {
        val run = FsCrawlOrchestratorRun().apply {
            this.triggeredBy = triggeredBy
            this.startedAt = OffsetDateTime.now()
            this.runStatus = FsCrawlOrchestratorRunStatus.RUNNING
            this.uri = "tempo:fs-orchestrator-run:${System.currentTimeMillis()}:${System.nanoTime()}"
            this.label = "FS crawl sweep ${this.startedAt}"
        }
        return orchestratorRunRepository.save(run)
    }

    @Transactional
    fun findRun(runId: Long): FsCrawlOrchestratorRun? =
        orchestratorRunRepository.findByIdWithOutcomes(runId)

    fun isSweepInFlight(): Boolean =
        orchestratorRunRepository.countByRunStatus(FsCrawlOrchestratorRunStatus.RUNNING) > 0L

    @Transactional
    fun setRunTotalCrawls(runId: Long, total: Int) {
        val run = orchestratorRunRepository.findById(runId)
            .orElseThrow { IllegalStateException("FsCrawlOrchestratorRun id=$runId not found") }
        run.totalCrawls = total
        orchestratorRunRepository.save(run)
    }

    @Transactional
    fun finishRun(runId: Long, status: FsCrawlOrchestratorRunStatus): FsCrawlOrchestratorRun {
        val run = orchestratorRunRepository.findById(runId)
            .orElseThrow { IllegalStateException("FsCrawlOrchestratorRun id=$runId not found") }
        run.finishedAt = OffsetDateTime.now()
        run.runStatus = status
        return orchestratorRunRepository.save(run)
    }

    @Transactional
    fun markRunFailed(runId: Long, reason: String) {
        val run = orchestratorRunRepository.findById(runId).orElse(null) ?: return
        run.finishedAt = OffsetDateTime.now()
        run.runStatus = FsCrawlOrchestratorRunStatus.FAILED
        run.description = (run.description?.let { "$it; " } ?: "") + "Aborted: $reason"
        orchestratorRunRepository.save(run)
    }

    /**
     * Returned from [submitAllEnabledCrawls] so callers (REST, schedulers)
     * can hand operators the polling id and tell them whether a new
     * sweep actually started or one was already underway.
     */
    data class SweepHandle(
        val runId: Long,
        val alreadyRunning: Boolean
    )
}
