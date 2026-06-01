package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.repos.MirrorConfigRepository
import com.oconeco.spring_search_tempo.base.service.MirrorCheckpointService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * MirrorJob lifecycle hooks:
 *  - **beforeJob**: stamp `MirrorConfig.lastRunStartedAt = now()`, clear
 *    any stale `lastError`.
 *  - **afterJob (COMPLETED)**: clear the `MirrorCheckpoint`, stamp
 *    `lastRunCompletedAt = now()`.
 *  - **afterJob (FAILED/STOPPED)**: leave the checkpoint in place so
 *    the next run resumes mid-folder, and record `lastError` from the
 *    job's failure exceptions.
 */
@Component
class MirrorJobLifecycleListener(
    private val checkpointService: MirrorCheckpointService,
    private val mirrorConfigRepository: MirrorConfigRepository
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(MirrorJobLifecycleListener::class.java)
        const val MIRROR_CONFIG_ID_KEY = "mirrorConfigId"
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
    }

    @Transactional
    override fun afterJob(jobExecution: JobExecution) {
        val mirrorConfigId = jobExecution.jobParameters
            .getLong(MIRROR_CONFIG_ID_KEY)
            ?: return

        when (jobExecution.status) {
            BatchStatus.COMPLETED -> {
                checkpointService.clear(mirrorConfigId)
                mirrorConfigRepository.findById(mirrorConfigId).ifPresent { entity ->
                    entity.lastRunCompletedAt = OffsetDateTime.now()
                    entity.lastError = null
                    mirrorConfigRepository.save(entity)
                }
                log.info("MirrorJob completed: mirrorConfigId={}", mirrorConfigId)
            }
            else -> {
                val errorMessage = jobExecution.allFailureExceptions
                    .joinToString("; ") { it.message ?: it.javaClass.simpleName }
                    .takeIf { it.isNotEmpty() }
                mirrorConfigRepository.findById(mirrorConfigId).ifPresent { entity ->
                    entity.lastError = errorMessage ?: "MirrorJob ended with status ${jobExecution.status}"
                    mirrorConfigRepository.save(entity)
                }
                log.warn(
                    "MirrorJob ended with status={} for mirrorConfigId={} — checkpoint preserved",
                    jobExecution.status, mirrorConfigId
                )
            }
        }
    }
}
