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
 */
data class ConfiguredJobDTO(
    val jobName: String,
    val description: String,
    val totalExecutions: Long,
    val lastExecutionTime: Instant?,
    val lastStatus: String?
)
