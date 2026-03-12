package com.oconeco.spring_search_tempo.web.model

import com.oconeco.spring_search_tempo.base.model.JobRunDTO
import java.time.Duration
import java.time.Instant

/**
 * Summary of a Spring Batch JobExecution for list views.
 */
data class JobExecutionSummaryDTO(
    val executionId: Long,
    val instanceId: Long,
    val jobName: String,
    val status: String,
    val exitCode: String?,
    val startTime: Instant?,
    val endTime: Instant?,
    val duration: Duration?,
    val jobRunId: Long?,
    val parameters: Map<String, String>
)

/**
 * Details of a Spring Batch StepExecution.
 */
data class StepExecutionDTO(
    val stepName: String,
    val status: String,
    val readCount: Long,
    val writeCount: Long,
    val skipCount: Long,
    val commitCount: Long,
    val rollbackCount: Long,
    val filterCount: Long,
    val startTime: Instant?,
    val endTime: Instant?,
    val duration: Duration?,
    val exitDescription: String?
)

/**
 * Full details of a Spring Batch JobExecution including steps.
 */
data class JobExecutionDetailDTO(
    val execution: JobExecutionSummaryDTO,
    val steps: List<StepExecutionDTO>,
    val jobRun: JobRunDTO?,
    val failureExceptions: List<String>
)

/**
 * Summary of job counts by status for the dashboard.
 */
data class BatchJobSummaryDTO(
    val totalExecutions: Long,
    val runningCount: Long,
    val completedCount: Long,
    val failedCount: Long,
    val stoppedCount: Long,
    val staleCount: Int = 0,
    /** Jobs detected as stale by heartbeat (no heartbeat in last 2 min) */
    val staleByHeartbeatCount: Int = 0
)

/**
 * Represents a configured/known batch job type with its execution statistics.
 * These are jobs that have been run at least once.
 */
data class ConfiguredJobDTO(
    val jobName: String,
    val description: String,
    val totalExecutions: Long,
    val lastExecutionTime: Instant?,
    val lastStatus: String?
)

/**
 * Represents an available job type that can be launched.
 * This includes both static beans and dynamic job builders.
 */
data class AvailableJobTypeDTO(
    /** Unique identifier for this job type (e.g., "fsCrawlJob", "nlpProcessingJob") */
    val jobTypeId: String,
    /** Human-readable name */
    val displayName: String,
    /** Brief description of what this job does */
    val description: String,
    /** Category for grouping (e.g., "Crawl", "Processing", "Sync") */
    val category: String,
    /** Whether this job has been run at least once */
    val hasRunHistory: Boolean,
    /** Total executions if any */
    val totalExecutions: Long,
    /** Last run timestamp if any */
    val lastExecutionTime: Instant?,
    /** Last status if any */
    val lastStatus: String?,
    /** Whether this job can be run directly from the batch admin (vs requiring special params) */
    val canRunDirectly: Boolean,
    /** Link to where to run this job if not directly runnable (e.g., "/crawlConfigs") */
    val runLink: String?,
    /** Whether any instance of this job type is currently running */
    val isRunning: Boolean = false,
    /** Execution IDs of currently running instances (for stop functionality) */
    val runningExecutionIds: List<Long> = emptyList(),
    /** Count of currently running instances */
    val runningCount: Int = 0
)

/**
 * Lightweight operational snapshot for the Batch dashboard.
 */
data class BatchOpsSnapshotDTO(
    /** Current running JobRuns from app-level tracking */
    val runningNow: Long,
    /** Running JobRuns considered stale by heartbeat threshold */
    val staleRunningNow: Long,
    /** JobRuns started in the last 15 minutes */
    val startedLast15m: Long,
    /** JobRuns completed successfully in the last 15 minutes */
    val completedLast15m: Long,
    /** JobRuns failed in the last 15 minutes */
    val failedLast15m: Long,
    /** Success percent from completed/failed in the last 15 minutes */
    val successRateLast15m: Int?,
    /** Mean completed duration over last 24h as preformatted text */
    val avgCompletedDurationLast24h: String?
)
