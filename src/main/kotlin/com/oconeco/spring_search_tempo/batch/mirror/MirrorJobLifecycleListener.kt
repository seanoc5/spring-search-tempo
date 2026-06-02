package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.events.MirrorJobCompletedEvent
import com.oconeco.spring_search_tempo.base.repos.MirrorConfigRepository
import com.oconeco.spring_search_tempo.base.service.MirrorCheckpointService
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

        when (jobExecution.status) {
            BatchStatus.COMPLETED -> {
                checkpointService.clear(mirrorConfigId)
                val completedAt = OffsetDateTime.now()
                val destAccountId = mirrorConfigRepository.findById(mirrorConfigId)
                    .map { entity ->
                        entity.lastRunCompletedAt = completedAt
                        entity.lastError = null
                        mirrorConfigRepository.save(entity)
                        entity.destAccountId
                    }
                    .orElse(null)
                completeJobRun(jobExecution, RunStatus.COMPLETED, errorMessage = null)
                log.info("MirrorJob completed: mirrorConfigId={}", mirrorConfigId)
                publishCompletedEvent(jobExecution, mirrorConfigId, destAccountId, completedAt)
            }
            else -> {
                val errorMessage = jobExecution.allFailureExceptions
                    .joinToString("; ") { it.message ?: it.javaClass.simpleName }
                    .takeIf { it.isNotEmpty() }
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
                    "MirrorJob ended with status={} for mirrorConfigId={} — checkpoint preserved",
                    jobExecution.status, mirrorConfigId
                )
            }
        }
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
