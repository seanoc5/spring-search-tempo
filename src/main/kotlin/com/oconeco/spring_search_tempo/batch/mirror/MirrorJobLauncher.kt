package com.oconeco.spring_search_tempo.batch.mirror

import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.stereotype.Service

/**
 * Convenience launcher for `mirrorJob`. Adds a timestamp parameter so the
 * Spring Batch job repository accepts the same `mirrorConfigId` across runs.
 */
@Service
class MirrorJobLauncher(
    private val jobLauncher: JobLauncher,
    private val mirrorJob: Job
) {
    companion object {
        private val log = LoggerFactory.getLogger(MirrorJobLauncher::class.java)
    }

    fun launch(mirrorConfigId: Long, triggeredBy: String = "manual"): JobExecution {
        val params = JobParametersBuilder()
            .addLong("mirrorConfigId", mirrorConfigId)
            .addLong("timestamp", System.currentTimeMillis())
            .addString("triggeredBy", triggeredBy)
            .toJobParameters()
        log.info("Launching mirrorJob: mirrorConfigId={} triggeredBy={}", mirrorConfigId, triggeredBy)
        return jobLauncher.run(mirrorJob, params)
    }
}
