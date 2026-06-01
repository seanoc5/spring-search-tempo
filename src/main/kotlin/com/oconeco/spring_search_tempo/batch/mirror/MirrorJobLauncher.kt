package com.oconeco.spring_search_tempo.batch.mirror

import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException
import org.springframework.stereotype.Service

/**
 * Convenience launcher for `mirrorJob`. Adds a timestamp parameter so the
 * Spring Batch job repository accepts the same `mirrorConfigId` across
 * runs, but pre-checks via [JobExplorer] for an active execution against
 * the same `mirrorConfigId` and rejects concurrent launches — without
 * this gate the unique-timestamp identifier would let two simultaneous
 * POSTs spawn two parallel jobs whose readers both pre-filter against
 * the same `MirroredMessage` snapshot and race on APPEND. The downstream
 * `MirroredMessage` unique constraint catches the audit-row collision,
 * but only after the destination IMAP server has accepted both
 * APPENDs — i.e. after the duplicate is already on the wire.
 */
@Service
class MirrorJobLauncher(
    private val jobLauncher: JobLauncher,
    private val mirrorJob: Job,
    private val jobExplorer: JobExplorer
) {
    companion object {
        private val log = LoggerFactory.getLogger(MirrorJobLauncher::class.java)
    }

    fun launch(mirrorConfigId: Long, triggeredBy: String = "manual"): JobExecution {
        val active: JobExecution? = jobExplorer.findRunningJobExecutions("mirrorJob")
            .firstOrNull { exec ->
                val paramId: Long? = exec.jobParameters.getLong("mirrorConfigId")
                paramId == mirrorConfigId
            }
        active?.let {
            throw JobExecutionAlreadyRunningException(
                "MirrorJob already running for mirrorConfigId=$mirrorConfigId (executionId=${it.id})"
            )
        }

        val params = JobParametersBuilder()
            .addLong("mirrorConfigId", mirrorConfigId)
            .addLong("timestamp", System.currentTimeMillis())
            .addString("triggeredBy", triggeredBy)
            .toJobParameters()
        log.info("Launching mirrorJob: mirrorConfigId={} triggeredBy={}", mirrorConfigId, triggeredBy)
        return jobLauncher.run(mirrorJob, params)
    }
}
