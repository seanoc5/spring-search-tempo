package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.model.JobRunDTO
import com.oconeco.spring_search_tempo.web.model.BatchJobSummaryDTO
import com.oconeco.spring_search_tempo.web.model.ConfiguredJobDTO
import com.oconeco.spring_search_tempo.web.model.JobExecutionDetailDTO
import com.oconeco.spring_search_tempo.web.model.JobExecutionSummaryDTO
import com.oconeco.spring_search_tempo.web.model.StepExecutionDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.repository.JobRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class BatchAdminService(
    private val jobExplorer: JobExplorer,
    private val jobOperator: JobOperator,
    private val jobRepository: JobRepository,
    private val jobRunService: JobRunService
) {
    private val log = LoggerFactory.getLogger(BatchAdminService::class.java)

    companion object {
        /** Default threshold for considering a running job as stale (orphaned) based on start time */
        const val DEFAULT_STALE_HOURS = 4L

        /** Threshold for heartbeat-based stale detection (more accurate) */
        const val HEARTBEAT_STALE_MINUTES = 2L
    }

    /**
     * Get all registered job names.
     */
    fun getJobNames(): List<String> {
        return jobExplorer.jobNames.toList().sorted()
    }

    /**
     * Get configured jobs with their execution statistics.
     * Groups jobs by their base name (e.g., "fsCrawlJob", "nlpProcessingJob").
     */
    fun getConfiguredJobs(): List<ConfiguredJobDTO> {
        val jobDescriptions = mapOf(
            "fsCrawlJob" to "File system crawl",
            "nlpProcessingJob" to "NLP text analysis",
            "emailQuickSync" to "Email synchronization",
            "bookmarkImportJob" to "Bookmark import"
        )

        return jobExplorer.jobNames.map { jobName ->
            // Only get recent instances (last 10) to find latest execution
            val recentInstances = jobExplorer.getJobInstances(jobName, 0, 10)
            val recentExecutions = recentInstances.flatMap { jobExplorer.getJobExecutions(it) }
            val lastExecution = recentExecutions.maxByOrNull { it.startTime ?: LocalDateTime.MIN }

            // Normalize job name to base type (strip suffixes like _1234)
            val baseJobName = jobName.substringBefore("_").takeIf { it.isNotEmpty() } ?: jobName
            val description = jobDescriptions[baseJobName] ?: baseJobName

            // Use count of recent executions (not total - that would require loading all)
            ConfiguredJobDTO(
                jobName = jobName,
                description = description,
                totalExecutions = recentExecutions.size.toLong(),  // Recent count only
                lastExecutionTime = lastExecution?.startTime?.atZone(ZoneId.systemDefault())?.toInstant(),
                lastStatus = lastExecution?.status?.name
            )
        }.sortedByDescending { it.lastExecutionTime }
    }

    /**
     * Get paginated job executions filtered by status.
     */
    fun getJobExecutionsByStatus(status: String, pageable: Pageable): Page<JobExecutionSummaryDTO> {
        val targetStatuses = when (status.uppercase()) {
            "RUNNING" -> setOf(BatchStatus.STARTED, BatchStatus.STARTING)
            "COMPLETED" -> setOf(BatchStatus.COMPLETED)
            "FAILED" -> setOf(BatchStatus.FAILED)
            "STOPPED" -> setOf(BatchStatus.STOPPED, BatchStatus.STOPPING)
            else -> return getAllJobExecutions(pageable)
        }

        val filtered = mutableListOf<JobExecution>()
        for (jobName in jobExplorer.jobNames) {
            val instances = jobExplorer.getJobInstances(jobName, 0, Int.MAX_VALUE)
            for (instance in instances) {
                jobExplorer.getJobExecutions(instance)
                    .filter { it.status in targetStatuses }
                    .forEach { filtered.add(it) }
            }
        }

        filtered.sortByDescending { it.startTime }

        val start = pageable.offset.toInt().coerceAtMost(filtered.size)
        val end = (start + pageable.pageSize).coerceAtMost(filtered.size)
        val pageContent = filtered.subList(start, end).map { toSummaryDTO(it) }

        return PageImpl(pageContent, pageable, filtered.size.toLong())
    }

    /**
     * Get paginated job executions across all jobs, sorted by start time descending.
     */
    fun getAllJobExecutions(pageable: Pageable): Page<JobExecutionSummaryDTO> {
        val allExecutions = mutableListOf<JobExecution>()

        for (jobName in jobExplorer.jobNames) {
            val instances = jobExplorer.getJobInstances(jobName, 0, Int.MAX_VALUE)
            for (instance in instances) {
                allExecutions.addAll(jobExplorer.getJobExecutions(instance))
            }
        }

        // Sort by start time descending (most recent first)
        allExecutions.sortByDescending { it.startTime }

        // Manual pagination
        val start = pageable.offset.toInt().coerceAtMost(allExecutions.size)
        val end = (start + pageable.pageSize).coerceAtMost(allExecutions.size)
        val pageContent = allExecutions.subList(start, end).map { toSummaryDTO(it) }

        return PageImpl(pageContent, pageable, allExecutions.size.toLong())
    }

    /**
     * Get job executions for a specific job name.
     */
    fun getJobExecutionsByJobName(jobName: String, pageable: Pageable): Page<JobExecutionSummaryDTO> {
        val instances = jobExplorer.getJobInstances(jobName, 0, Int.MAX_VALUE)
        val allExecutions = mutableListOf<JobExecution>()

        for (instance in instances) {
            allExecutions.addAll(jobExplorer.getJobExecutions(instance))
        }

        allExecutions.sortByDescending { it.startTime }

        val start = pageable.offset.toInt().coerceAtMost(allExecutions.size)
        val end = (start + pageable.pageSize).coerceAtMost(allExecutions.size)
        val pageContent = allExecutions.subList(start, end).map { toSummaryDTO(it) }

        return PageImpl(pageContent, pageable, allExecutions.size.toLong())
    }

    /**
     * Get running job executions.
     */
    fun getRunningJobExecutions(): List<JobExecutionSummaryDTO> {
        val running = mutableListOf<JobExecution>()

        for (jobName in jobExplorer.jobNames) {
            running.addAll(jobExplorer.findRunningJobExecutions(jobName))
        }

        return running.sortedByDescending { it.startTime }.map { toSummaryDTO(it) }
    }

    /**
     * Get failed job executions.
     */
    fun getFailedJobExecutions(pageable: Pageable): Page<JobExecutionSummaryDTO> {
        val failed = mutableListOf<JobExecution>()

        for (jobName in jobExplorer.jobNames) {
            val instances = jobExplorer.getJobInstances(jobName, 0, Int.MAX_VALUE)
            for (instance in instances) {
                jobExplorer.getJobExecutions(instance)
                    .filter { it.status == BatchStatus.FAILED }
                    .forEach { failed.add(it) }
            }
        }

        failed.sortByDescending { it.startTime }

        val start = pageable.offset.toInt().coerceAtMost(failed.size)
        val end = (start + pageable.pageSize).coerceAtMost(failed.size)
        val pageContent = failed.subList(start, end).map { toSummaryDTO(it) }

        return PageImpl(pageContent, pageable, failed.size.toLong())
    }

    /**
     * Get a single job execution with full details including steps.
     */
    fun getJobExecution(executionId: Long): JobExecutionDetailDTO? {
        val execution = jobExplorer.getJobExecution(executionId) ?: return null

        val steps = execution.stepExecutions.sortedBy { it.startTime }.map { toStepDTO(it) }

        val failureExceptions = execution.allFailureExceptions.map {
            it.message ?: it.javaClass.simpleName
        }

        // Try to find associated JobRun
        val jobRun = findJobRunByExecutionId(executionId)

        return JobExecutionDetailDTO(
            execution = toSummaryDTO(execution),
            steps = steps,
            jobRun = jobRun,
            failureExceptions = failureExceptions
        )
    }

    /**
     * Get summary counts for the batch dashboard.
     * Uses optimized counting via running job lookup and recent executions sampling.
     */
    fun getJobSummary(): BatchJobSummaryDTO {
        // Count running jobs (small set, fast lookup)
        var running = 0L
        for (jobName in jobExplorer.jobNames) {
            running += jobExplorer.findRunningJobExecutions(jobName).size
        }

        // For total/completed/failed/stopped counts, query recent executions only
        // This avoids loading thousands of old executions
        val recentExecutions = mutableListOf<JobExecution>()
        for (jobName in jobExplorer.jobNames) {
            // Get only recent instances (last 100 per job type)
            val instances = jobExplorer.getJobInstances(jobName, 0, 100)
            for (instance in instances) {
                recentExecutions.addAll(jobExplorer.getJobExecutions(instance))
            }
        }

        var completed = 0L
        var failed = 0L
        var stopped = 0L

        for (execution in recentExecutions) {
            when (execution.status) {
                BatchStatus.COMPLETED -> completed++
                BatchStatus.FAILED -> failed++
                BatchStatus.STOPPED, BatchStatus.STOPPING -> stopped++
                else -> {}
            }
        }

        // Total is running + counted from recent
        val total = running + completed + failed + stopped

        return BatchJobSummaryDTO(
            totalExecutions = total,
            runningCount = running,
            completedCount = completed,
            failedCount = failed,
            stoppedCount = stopped
        )
    }

    /**
     * Stop a running job execution.
     * Returns true if stop was initiated, false if job wasn't running.
     */
    fun stopJob(executionId: Long): Boolean {
        return try {
            val execution = jobExplorer.getJobExecution(executionId) ?: run {
                log.warn("Cannot stop job execution {} - not found", executionId)
                return false
            }
            if (execution.isRunning) {
                jobOperator.stop(executionId)
                log.info("Stop requested for job execution {}", executionId)
                true
            } else {
                log.warn("Cannot stop job execution {} - not running", executionId)
                false
            }
        } catch (e: Exception) {
            log.error("Failed to stop job execution {}: {}", executionId, e.message)
            false
        }
    }

    /**
     * Stop all currently running job executions.
     * Returns the number of executions for which stop was successfully requested.
     */
    fun stopAllRunningJobs(): Int {
        val runningIds = getRunningJobExecutions().map { it.executionId }.distinct()
        var stopped = 0
        for (executionId in runningIds) {
            if (stopJob(executionId)) {
                stopped++
            }
        }
        return stopped
    }

    /**
     * Restart a failed or stopped job execution.
     * Returns the new execution ID if restart was successful, null otherwise.
     *
     * Note: This only works for jobs registered in the JobRegistry. Our dynamically
     * created crawl jobs are not registered, so restart won't work for them.
     * Users should re-run crawls through the Crawl Config UI instead.
     */
    fun restartJob(executionId: Long): Long? {
        return try {
            val execution = jobExplorer.getJobExecution(executionId) ?: run {
                log.warn("Cannot restart job execution {} - not found", executionId)
                return null
            }
            val status = execution.status
            if (status == BatchStatus.FAILED || status == BatchStatus.STOPPED) {
                val newExecutionId = jobOperator.restart(executionId)
                log.info("Restarted job execution {} as new execution {}", executionId, newExecutionId)
                newExecutionId
            } else {
                log.warn("Cannot restart job execution {} - status is {}", executionId, status)
                null
            }
        } catch (e: org.springframework.batch.core.launch.NoSuchJobException) {
            log.warn("Cannot restart job execution {} - job not registered. Re-run via Crawl Config UI.", executionId)
            null
        } catch (e: Exception) {
            log.error("Failed to restart job execution {}: {}", executionId, e.message)
            null
        }
    }

    /**
     * Abandon a failed job execution so it can be restarted fresh.
     *
     * Note: This only works for jobs registered in the JobRegistry. Our dynamically
     * created crawl jobs are not registered.
     */
    fun abandonJob(executionId: Long): Boolean {
        return try {
            val execution = jobExplorer.getJobExecution(executionId) ?: run {
                log.warn("Cannot abandon job execution {} - not found", executionId)
                return false
            }
            val status = execution.status
            if (status == BatchStatus.FAILED) {
                jobOperator.abandon(executionId)
                log.info("Abandoned job execution {}", executionId)
                true
            } else {
                log.warn("Cannot abandon job execution {} - status is {}", executionId, status)
                false
            }
        } catch (e: org.springframework.batch.core.launch.NoSuchJobException) {
            log.warn("Cannot abandon job execution {} - job not registered", executionId)
            false
        } catch (e: Exception) {
            log.error("Failed to abandon job execution {}: {}", executionId, e.message)
            false
        }
    }

    // ========== Stale Job Detection and Cleanup ==========

    /**
     * Get all stale (orphaned) job executions.
     * A job is considered stale if it's been running for more than the threshold hours.
     * This typically happens when the app crashes or is forcefully stopped.
     */
    fun getStaleJobExecutions(staleThresholdHours: Long = DEFAULT_STALE_HOURS): List<JobExecutionSummaryDTO> {
        val staleThreshold = LocalDateTime.now().minusHours(staleThresholdHours)
        val stale = mutableListOf<JobExecution>()

        for (jobName in jobExplorer.jobNames) {
            for (execution in jobExplorer.findRunningJobExecutions(jobName)) {
                val startTime = execution.startTime
                if (startTime != null && startTime.isBefore(staleThreshold)) {
                    stale.add(execution)
                }
            }
        }

        return stale.sortedByDescending { it.startTime }.map { toSummaryDTO(it) }
    }

    /**
     * Get count of stale job executions that will be cleaned up.
     *
     * Uses fast queries only:
     * 1. Heartbeat-based: Jobs with JobRun tracking where lastHeartbeatAt > 2 minutes ago (DB query)
     * 2. Start time-based: Running jobs older than threshold (only checks running jobs)
     *
     * Note: STOPPING jobs are handled during cleanup but not counted here to avoid
     * expensive iteration through all job executions.
     */
    fun getStaleJobCount(staleThresholdHours: Long = DEFAULT_STALE_HOURS): Int {
        // 1. Count heartbeat-based stale jobs (fast DB query)
        val staleByHeartbeat = jobRunService.findStaleJobRuns(HEARTBEAT_STALE_MINUTES)

        // 2. Count start time-based stale jobs (only iterates running jobs, not all)
        val staleThreshold = LocalDateTime.now().minusHours(staleThresholdHours)
        var staleByStartTime = 0
        for (jobName in jobExplorer.jobNames) {
            for (execution in jobExplorer.findRunningJobExecutions(jobName)) {
                val startTime = execution.startTime
                if (startTime != null && startTime.isBefore(staleThreshold)) {
                    staleByStartTime++
                }
            }
        }

        // Return higher of the two counts
        return maxOf(staleByHeartbeat.size, staleByStartTime)
    }

    /**
     * Get IDs of JobRuns that are stale based on heartbeat.
     * These are jobs that haven't sent a heartbeat in the last 2 minutes.
     */
    fun getStaleJobRunIds(): List<Long> {
        return jobRunService.findStaleJobRuns(HEARTBEAT_STALE_MINUTES)
    }

    /**
     * Mark a single job execution as FAILED.
     * This is useful for cleaning up orphaned jobs that appear as "running"
     * but are actually dead due to app crashes.
     *
     * Handles jobs in: STARTED, STARTING, or STOPPING status.
     */
    @Transactional
    fun markJobAsFailed(executionId: Long, reason: String = "Marked as failed by admin"): Boolean {
        return try {
            val execution = jobExplorer.getJobExecution(executionId) ?: run {
                log.warn("Cannot mark job execution {} as failed - not found", executionId)
                return false
            }

            // Allow marking as failed if running OR stuck in STOPPING
            val canMarkFailed = execution.isRunning || execution.status == BatchStatus.STOPPING
            if (!canMarkFailed) {
                log.warn("Job execution {} cannot be marked as failed (status: {}), skipping", executionId, execution.status)
                return false
            }

            // Update job execution status
            execution.status = BatchStatus.FAILED
            execution.exitStatus = ExitStatus.FAILED.addExitDescription(reason)
            execution.setEndTime(LocalDateTime.now())

            // Also update any running or stopping step executions
            for (step in execution.stepExecutions) {
                if (step.status == BatchStatus.STARTED || step.status == BatchStatus.STARTING || step.status == BatchStatus.STOPPING) {
                    step.status = BatchStatus.FAILED
                    step.exitStatus = ExitStatus.FAILED.addExitDescription("Parent job marked as failed")
                    step.setEndTime(LocalDateTime.now())
                    jobRepository.update(step)
                }
            }

            jobRepository.update(execution)
            log.info("Marked job execution {} as FAILED: {}", executionId, reason)
            true
        } catch (e: Exception) {
            log.error("Failed to mark job execution {} as failed: {}", executionId, e.message)
            false
        }
    }

    /**
     * Mark all running job executions as FAILED.
     * Returns the number of executions updated.
     */
    @Transactional
    fun markAllRunningJobsAsFailed(reason: String = "Bulk marked as failed by admin"): Int {
        val runningIds = getRunningJobExecutions().map { it.executionId }.distinct()
        var failed = 0
        for (executionId in runningIds) {
            if (markJobAsFailed(executionId, reason)) {
                failed++
            }
        }
        return failed
    }

    /**
     * Clean up all stale job executions by marking them as FAILED.
     * Uses two-tier detection:
     * 1. Heartbeat-based: Jobs with JobRun tracking where lastHeartbeatAt > 2 minutes ago
     * 2. Start time-based: Legacy fallback for jobs without heartbeat (> 4 hours)
     * Also handles jobs stuck in STOPPING status.
     *
     * Returns the number of jobs that were cleaned up.
     */
    @Transactional
    fun cleanupStaleJobs(staleThresholdHours: Long = DEFAULT_STALE_HOURS): Int {
        var cleanedUp = 0
        val processedExecutionIds = mutableSetOf<Long>()

        // 1. Clean up heartbeat-detected stale jobs (most accurate)
        val staleJobRunIds = jobRunService.findStaleJobRuns(HEARTBEAT_STALE_MINUTES)
        log.info("Found {} stale JobRuns by heartbeat", staleJobRunIds.size)

        for (jobRunId in staleJobRunIds) {
            try {
                val executionIdStr = jobRunService.markAsFailed(
                    jobRunId,
                    "Stale job cleanup - no heartbeat for $HEARTBEAT_STALE_MINUTES+ minutes"
                )

                // Also mark the associated Spring Batch execution
                if (executionIdStr != null) {
                    val executionId = executionIdStr.toLongOrNull()
                    if (executionId != null) {
                        markJobAsFailed(executionId, "Stale job cleanup - no heartbeat for $HEARTBEAT_STALE_MINUTES+ minutes")
                        processedExecutionIds.add(executionId)
                    }
                }
                cleanedUp++
            } catch (e: Exception) {
                log.warn("Failed to clean up stale JobRun {}: {}", jobRunId, e.message)
            }
        }

        // 2. Clean up jobs stuck in STOPPING status (can't be stopped again)
        for (jobName in jobExplorer.jobNames) {
            val instances = jobExplorer.getJobInstances(jobName, 0, Int.MAX_VALUE)
            for (instance in instances) {
                for (execution in jobExplorer.getJobExecutions(instance)) {
                    if (execution.status == BatchStatus.STOPPING && execution.id !in processedExecutionIds) {
                        log.info("Cleaning up job stuck in STOPPING: {} (execution {})", jobName, execution.id)
                        if (markJobAsFailed(execution.id, "Cleanup - job stuck in STOPPING status")) {
                            processedExecutionIds.add(execution.id)
                            cleanedUp++
                        }
                    }
                }
            }
        }

        // 3. Fallback: Clean up start-time-based stale jobs (for jobs without heartbeat tracking)
        val staleThreshold = LocalDateTime.now().minusHours(staleThresholdHours)
        for (jobName in jobExplorer.jobNames) {
            for (execution in jobExplorer.findRunningJobExecutions(jobName)) {
                if (execution.id in processedExecutionIds) continue

                val startTime = execution.startTime
                if (startTime != null && startTime.isBefore(staleThreshold)) {
                    val hours = Duration.between(startTime, LocalDateTime.now()).toHours()
                    if (markJobAsFailed(execution.id, "Stale job cleanup - running for $hours hours")) {
                        cleanedUp++
                    }
                }
            }
        }

        log.info("Cleaned up {} stale job executions total", cleanedUp)
        return cleanedUp
    }

    /**
     * Find the associated JobRun (app-specific tracking) for a Spring Batch execution.
     */
    private fun findJobRunByExecutionId(executionId: Long): JobRunDTO? {
        return try {
            // Search through recent job runs to find one matching this execution ID
            val jobRuns = jobRunService.findAll(null, Pageable.ofSize(1000))
            jobRuns.content.find {
                it.springBatchJobExecutionId == executionId.toString()
            }
        } catch (e: Exception) {
            log.debug("Could not find JobRun for execution {}: {}", executionId, e.message)
            null
        }
    }

    private fun toSummaryDTO(execution: JobExecution): JobExecutionSummaryDTO {
        val startTime = execution.startTime?.atZone(ZoneId.systemDefault())?.toInstant()
        val endTime = execution.endTime?.atZone(ZoneId.systemDefault())?.toInstant()
        val duration = if (startTime != null && endTime != null) {
            Duration.between(startTime, endTime)
        } else if (startTime != null) {
            Duration.between(startTime, java.time.Instant.now())
        } else {
            null
        }

        val parameters = execution.jobParameters.parameters.mapValues { (_, param) ->
            param.value?.toString() ?: ""
        }

        // Try to find associated JobRun ID from parameters
        val jobRunId = findJobRunByExecutionId(execution.id)?.id

        return JobExecutionSummaryDTO(
            executionId = execution.id,
            instanceId = execution.jobInstance.instanceId,
            jobName = execution.jobInstance.jobName,
            status = execution.status.name,
            exitCode = execution.exitStatus?.exitCode,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            jobRunId = jobRunId,
            parameters = parameters
        )
    }

    private fun toStepDTO(step: StepExecution): StepExecutionDTO {
        val startTime = step.startTime?.atZone(ZoneId.systemDefault())?.toInstant()
        val endTime = step.endTime?.atZone(ZoneId.systemDefault())?.toInstant()
        val duration = if (startTime != null && endTime != null) {
            Duration.between(startTime, endTime)
        } else if (startTime != null) {
            Duration.between(startTime, java.time.Instant.now())
        } else {
            null
        }

        return StepExecutionDTO(
            stepName = step.stepName,
            status = step.status.name,
            readCount = step.readCount,
            writeCount = step.writeCount,
            skipCount = step.skipCount,
            commitCount = step.commitCount,
            rollbackCount = step.rollbackCount,
            filterCount = step.filterCount,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            exitDescription = step.exitStatus?.exitDescription?.takeIf { it.isNotBlank() }
        )
    }
}
