package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.events.MirrorJobCompletedEvent
import com.oconeco.spring_search_tempo.base.repos.MirrorConfigRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorFolderProgressRepository
import com.oconeco.spring_search_tempo.base.service.MirrorCheckpointService
import com.oconeco.spring_search_tempo.base.service.MirrorFolderCheckpointService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * MirrorJob lifecycle hooks:
 *  - **beforeJob**: stamp `MirrorConfig.lastRunStartedAt = now()`, clear
 *    any stale `lastError`, and open a `JobRun` row tied to the mirror
 *    config so the progress dashboard (#26) can read start time, status,
 *    and processed counts from a single source. The `jobRunId` is
 *    written into the job's `ExecutionContext` so step components can
 *    fetch it without taking a second DB round-trip.
 *  - **afterJob (COMPLETED)**: clear the `MirrorCheckpoint`, stamp
 *    `lastRunCompletedAt = now()`, and mark the `JobRun` COMPLETED.
 *  - **afterJob (FAILED/STOPPED)**: leave the checkpoint in place so
 *    the next run resumes mid-folder, record `lastError` from the
 *    job's failure exceptions, and mark the `JobRun` FAILED/CANCELLED.
 */
@Component
class MirrorJobLifecycleListener(
    private val checkpointService: MirrorCheckpointService,
    private val folderCheckpointService: MirrorFolderCheckpointService,
    private val folderProgressRepository: MirrorFolderProgressRepository,
    private val mirrorConfigRepository: MirrorConfigRepository,
    private val jobRunService: JobRunService,
    private val eventPublisher: ApplicationEventPublisher
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(MirrorJobLifecycleListener::class.java)
        const val MIRROR_CONFIG_ID_KEY = "mirrorConfigId"
        const val JOB_RUN_ID_KEY = "jobRunId"
    }

    @Transactional
    override fun beforeJob(jobExecution: JobExecution) {
        val mirrorConfigId = jobExecution.jobParameters
            .getLong(MIRROR_CONFIG_ID_KEY)
            ?: return
        mirrorConfigRepository.findById(mirrorConfigId).ifPresent { entity ->
            entity.lastRunStartedAt = OffsetDateTime.now()
            entity.lastError = null
            mirrorConfigRepository.save(entity)
            log.info("MirrorJob starting: mirrorConfigId={} ({})", mirrorConfigId, entity.name)
        }

        try {
            val jobRunDTO = jobRunService.startJobRunForMirror(
                mirrorConfigId = mirrorConfigId,
                jobName = jobExecution.jobInstance.jobName
            )
            jobExecution.executionContext.putLong(JOB_RUN_ID_KEY, jobRunDTO.id!!)
            log.info("Opened JobRun {} for mirrorConfigId={}", jobRunDTO.id, mirrorConfigId)
        } catch (e: Exception) {
            log.error("Failed to open JobRun for mirrorConfigId={}", mirrorConfigId, e)
        }
    }

    @Transactional
    override fun afterJob(jobExecution: JobExecution) {
        val mirrorConfigId = jobExecution.jobParameters
            .getLong(MIRROR_CONFIG_ID_KEY)
            ?: return

        val jobRunId = jobExecution.executionContext.getLong(JOB_RUN_ID_KEY, -1L).takeIf { it > 0 }
        val (foldersSucceeded, foldersFailed, failedFolderNames) = summarizeFolders(jobRunId)
        log.info(
            "MirrorJob folder summary: mirrorConfigId={} foldersSucceeded={} foldersFailed={}{}",
            mirrorConfigId, foldersSucceeded, foldersFailed,
            if (failedFolderNames.isNotEmpty()) " failed=$failedFolderNames" else ""
        )

        when (jobExecution.status) {
            BatchStatus.COMPLETED -> {
                // Whole run finished cleanly — clear both the legacy
                // checkpoint and every per-folder watermark; folders
                // that failed mid-run already had their `MirrorError`
                // rows recorded by the reader.
                checkpointService.clear(mirrorConfigId)
                folderCheckpointService.clear(mirrorConfigId)
                val completedAt = OffsetDateTime.now()
                val destAccountId = mirrorConfigRepository.findById(mirrorConfigId)
                    .map { entity ->
                        entity.lastRunCompletedAt = completedAt
                        entity.lastError = if (foldersFailed > 0) {
                            "Partial: $foldersSucceeded folder(s) succeeded, $foldersFailed failed — $failedFolderNames"
                        } else null
                        mirrorConfigRepository.save(entity)
                        entity.destAccountId
                    }
                    .orElse(null)
                completeJobRun(jobExecution, RunStatus.COMPLETED, errorMessage = null)
                log.info(
                    "MirrorJob completed: mirrorConfigId={} foldersSucceeded={} foldersFailed={}",
                    mirrorConfigId, foldersSucceeded, foldersFailed
                )
                publishCompletedEvent(jobExecution, mirrorConfigId, destAccountId, completedAt)
            }
            else -> {
                // FAILED means every folder failed (reader's afterStep
                // gates the exit status) or a non-folder-scope error
                // killed the step. Either way: preserve the per-folder
                // watermarks so a retry replays just the unfinished
                // work.
                val errorMessage = jobExecution.allFailureExceptions
                    .joinToString("; ") { it.message ?: it.javaClass.simpleName }
                    .takeIf { it.isNotEmpty() }
                    ?: jobExecution.stepExecutions
                        .firstOrNull { it.exitStatus.exitCode == "FAILED" }
                        ?.exitStatus
                        ?.exitDescription
                        ?.takeIf { it.isNotEmpty() }
                mirrorConfigRepository.findById(mirrorConfigId).ifPresent { entity ->
                    entity.lastError = errorMessage ?: "MirrorJob ended with status ${jobExecution.status}"
                    mirrorConfigRepository.save(entity)
                }
                val runStatus = when (jobExecution.status) {
                    BatchStatus.STOPPED -> RunStatus.CANCELLED
                    else -> RunStatus.FAILED
                }
                completeJobRun(jobExecution, runStatus, errorMessage)
                log.warn(
                    "MirrorJob ended with status={} for mirrorConfigId={} — checkpoint preserved (foldersSucceeded={}, foldersFailed={})",
                    jobExecution.status, mirrorConfigId, foldersSucceeded, foldersFailed
                )
            }
        }
    }

    private data class FolderSummary(
        val succeeded: Int,
        val failed: Int,
        val failedNames: List<String>
    )

    /**
     * Read the per-folder progress rows the reader stamped during the
     * step and tally outcomes. Returns zeroes if the run never reached
     * the reader (no JobRun id), which keeps lifecycle bookkeeping
     * tolerant of bootstrap failures.
     */
    private fun summarizeFolders(jobRunId: Long?): FolderSummary {
        if (jobRunId == null) return FolderSummary(0, 0, emptyList())
        val rows = try {
            folderProgressRepository.findByJobRunId(jobRunId)
        } catch (e: Exception) {
            log.warn("Failed to load folder progress for jobRunId={}: {}", jobRunId, e.message)
            return FolderSummary(0, 0, emptyList())
        }
        val succeeded = rows.count { it.status == "COMPLETED" }
        val failed = rows.count { it.status == "FAILED" }
        val failedNames = rows.filter { it.status == "FAILED" }.mapNotNull { it.sourceFolder }
        return FolderSummary(succeeded, failed, failedNames)
    }

    private fun completeJobRun(jobExecution: JobExecution, runStatus: RunStatus, errorMessage: String?) {
        val jobRunId = jobExecution.executionContext.getLong(JOB_RUN_ID_KEY, -1L)
        if (jobRunId <= 0) return
        try {
            jobRunService.completeJobRun(jobRunId, runStatus, errorMessage)
        } catch (e: Exception) {
            log.error("Failed to finalize JobRun {} (status={})", jobRunId, runStatus, e)
        }
    }

    private fun publishCompletedEvent(
        jobExecution: JobExecution,
        mirrorConfigId: Long,
        destAccountId: Long?,
        completedAt: OffsetDateTime
    ) {
        val jobRunId = jobExecution.executionContext.getLong(JOB_RUN_ID_KEY, -1L).takeIf { it > 0 }
        val messagesMirrored = jobExecution.stepExecutions.sumOf { it.writeCount }
        try {
            eventPublisher.publishEvent(
                MirrorJobCompletedEvent(
                    mirrorConfigId = mirrorConfigId,
                    jobRunId = jobRunId,
                    destAccountId = destAccountId,
                    completedAt = completedAt,
                    messagesMirrored = messagesMirrored
                )
            )
            log.info(
                "Published MirrorJobCompletedEvent: mirrorConfigId={} jobRunId={} destAccountId={} messagesMirrored={}",
                mirrorConfigId, jobRunId, destAccountId, messagesMirrored
            )
        } catch (e: Exception) {
            log.error("Failed to publish MirrorJobCompletedEvent for mirrorConfigId={}", mirrorConfigId, e)
        }
    }
}
