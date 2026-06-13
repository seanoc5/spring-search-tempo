package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.base.domain.FolderAuditRun
import com.oconeco.spring_search_tempo.base.domain.FolderAuditRunStatus
import com.oconeco.spring_search_tempo.base.domain.FolderAuditSourceType
import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Coordinates folder-audit runs (issue #103).
 *
 * `startFilesystemAuditRun()` is the entry point used by the REST endpoint
 * and the admin "Run Folder Audit Now" button. It:
 *
 *   1. Creates a [FolderAuditRun] row with status RUNNING and persists it
 *      so the caller immediately has a `runId` to poll on.
 *   2. Launches `filesystemFolderAuditJob` (async — the project's
 *      `jobLauncher` is wired with a [org.springframework.core.task.SimpleAsyncTaskExecutor]
 *      in [com.oconeco.spring_search_tempo.batch.config.BatchTaskExecutorConfig]).
 *   3. Returns the run id; final aggregates and status land on the row
 *      when the tasklet completes.
 *
 * The job-launch failure path explicitly reaps the half-started row to
 * FAILED so the operator sees the error in the admin view instead of an
 * orphaned RUNNING row that never moves.
 */
@Service
class FolderAuditService(
    private val folderAuditRunRepository: FolderAuditRunRepository,
    private val crawlConfigService: CrawlConfigService,
    private val jobLauncher: JobLauncher,
    private val jobExplorer: JobExplorer,
    @Qualifier("filesystemFolderAuditJob") private val filesystemFolderAuditJob: Job
) {

    companion object {
        private val log = LoggerFactory.getLogger(FolderAuditService::class.java)
    }

    @Throws(JobExecutionAlreadyRunningException::class)
    fun startFilesystemAuditRun(): Long {
        // Dedup: a full-filesystem walk is expensive — refuse a second
        // concurrent launch (double-clicked admin button, runaway API
        // client) rather than fanning out duplicate walks.
        val running: Set<JobExecution> = jobExplorer.findRunningJobExecutions("filesystemFolderAuditJob")
        if (running.isNotEmpty()) {
            val active = running.first()
            val runningRunId = active.jobParameters.getLong("runId")
            throw JobExecutionAlreadyRunningException(
                "filesystemFolderAuditJob already running (executionId=${active.id}, runId=$runningRunId)"
            )
        }

        val enabledCrawls = crawlConfigService.getEnabledCrawls()
        val sourceRef = if (enabledCrawls.isEmpty()) {
            "(no enabled crawls)"
        } else {
            enabledCrawls.joinToString(",") { it.name }
        }

        val run = FolderAuditRun().apply {
            started = OffsetDateTime.now()
            sourceType = FolderAuditSourceType.FILESYSTEM
            this.sourceRef = sourceRef
            status = FolderAuditRunStatus.RUNNING
        }
        val saved = folderAuditRunRepository.save(run)
        val runId = saved.id ?: error("FolderAuditRun id not assigned after save")

        val params = JobParametersBuilder()
            .addLong("runId", runId)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()

        log.info("Launching filesystemFolderAuditJob: runId={}, sourceRef={}", runId, sourceRef)
        try {
            jobLauncher.run(filesystemFolderAuditJob, params)
        } catch (e: Exception) {
            log.error("Failed to launch filesystemFolderAuditJob for runId={}", runId, e)
            saved.status = FolderAuditRunStatus.FAILED
            saved.finished = OffsetDateTime.now()
            saved.errorMessage = "Job launch failed: ${e.message?.take(2000)}"
            folderAuditRunRepository.save(saved)
            throw e
        }
        return runId
    }
}
