package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

/**
 * Per-crawl-config wall-clock + resource snapshot captured by
 * [com.oconeco.spring_search_tempo.batch.fscrawl.FsCrawlOrchestrator] when
 * it sweeps every enabled crawl (issue #149).
 *
 * This is the measurement substrate the "Parallel FS crawl orchestration"
 * roadmap entry is gated on — without real wall-clock + resource numbers
 * from a single-threaded sweep, parallelism defaults are guesswork (Tika
 * thread-safety at N, NLP memory × N, Postgres connection-pool ceiling,
 * disk-IO contention).
 *
 * One row per `(crawl_config_id, started_at)` tuple — re-running the same
 * config produces a new row so the table can be sliced by run history.
 */
@Entity
@Table(
    name = "crawl_run_metrics",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_crawl_run_metrics_config_started",
            columnNames = ["crawl_config_id", "started_at"]
        )
    ],
    indexes = [
        Index(name = "idx_crawl_run_metrics_config", columnList = "crawl_config_id"),
        Index(name = "idx_crawl_run_metrics_started", columnList = "started_at"),
    ]
)
class CrawlRunMetrics : SaveableObject() {

    /** FK-style id (not a JPA relation — config may have been deleted by report time). */
    @Column(name = "crawl_config_id", nullable = false)
    var crawlConfigId: Long = 0

    @Column(columnDefinition = "text")
    var crawlConfigName: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "orchestrator_outcome_id")
    var orchestratorOutcome: FsCrawlOrchestratorOutcome? = null

    /** Spring Batch JobExecution id (string for parity with FsCrawlOrchestratorOutcome). */
    @Column(columnDefinition = "text")
    var jobExecutionId: String? = null

    // NB: `jobRunId` is inherited from SaveableObject — represents the
    // JobRun row produced by JobRunTrackingListener. Left as a property
    // there rather than redeclared here so it stays consistent with the
    // rest of the domain.

    @Column(name = "started_at", nullable = false)
    var startedAt: OffsetDateTime = OffsetDateTime.now()

    @Column
    var finishedAt: OffsetDateTime? = null

    @Column
    var durationMs: Long? = null

    @Column
    var filesVisited: Long = 0

    @Column
    var filesIndexed: Long = 0

    @Column
    var filesSkipped: Long = 0

    /** Files matching a SKIP pattern (metadata persisted, no further work). */
    @Column
    var filesLevelSkip: Long = 0

    /** Files at LOCATE level (metadata only). */
    @Column
    var filesLevelLocate: Long = 0

    /** Files at INDEX level (full text + metadata). */
    @Column
    var filesLevelIndex: Long = 0

    /** Files at ANALYZE level (INDEX + NLP). */
    @Column
    var filesLevelAnalyze: Long = 0

    /**
     * Sum of file sizes touched by this run (bytes). Sourced from
     * `fs_file.size` (the issue body calls this `byte_size`; the actual
     * inherited column on FSObject/SaveableObject is `size`).
     */
    @Column
    var bytesRead: Long = 0

    /**
     * Peak used heap during the run, sampled via
     * `ManagementFactory.getMemoryMXBean().heapMemoryUsage.used`.
     */
    @Column
    var peakHeapBytes: Long? = null

    /**
     * Peak HikariCP active connections during the run. Sampled via
     * `HikariPoolMXBean.activeConnections`. The number to watch when
     * sizing for parallelism — a single crawl rarely needs more than
     * 2-3 connections; a parallel sweep with N concurrent crawls
     * cannot exceed the configured pool maximum.
     */
    @Column
    var peakHikariActive: Int? = null

    /**
     * Tika extraction failures during this run — pulled from the
     * `filesError` counter aggregated by `CombinedCrawlWriter` (which
     * counts both I/O errors and Tika parse failures together; the
     * write-side doesn't currently distinguish, and over-reporting is
     * the safer direction for parallelism planning).
     */
    @Column
    var tikaFailures: Long = 0

    @Column(length = 32)
    var runStatus: String? = null

    @Column(columnDefinition = "text")
    var errorMessage: String? = null
}
