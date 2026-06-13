package com.oconeco.spring_search_tempo.batch.config

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.JobLifecycleEventType
import com.oconeco.spring_search_tempo.base.repos.JobLifecycleEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Verifies the graceful-shutdown hook (#75) marks the in-flight
 * `BatchJobExecution` as FAILED **and** writes a `job_lifecycle_event`
 * audit row with `event_type=SHUTDOWN` and `action_taken=stopped`.
 *
 * Uses the real [JobExecutionAdvisoryLockListener.beforeJob] path so
 * the hook's `heldExecutionIds()` view of in-flight jobs is populated
 * exactly the way it is in production.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("RunningJobShutdownHook lifecycle audit (issue #75)")
class RunningJobShutdownHookIT : BaseIT() {

    @Autowired
    lateinit var shutdownHook: RunningJobShutdownHook

    @Autowired
    lateinit var advisoryLockListener: JobExecutionAdvisoryLockListener

    @Autowired
    lateinit var jobRepository: JobRepository

    @Autowired
    lateinit var jobExplorer: JobExplorer

    @Autowired
    lateinit var jobLifecycleEventRepository: JobLifecycleEventRepository

    @Test
    @DisplayName("in-flight STARTED row → FAILED + SHUTDOWN audit row with action=stopped")
    fun marksInFlightJobFailedAndAudits() {
        val jobName = "shutdownHookIT_${System.nanoTime()}"
        val accountId = 5151L
        val params = JobParametersBuilder()
            .addLong("uniq", System.nanoTime())
            .addString("accountId", accountId.toString())
            .toJobParameters()

        val execution = jobRepository.createJobExecution(jobName, params)
        execution.status = BatchStatus.STARTED
        jobRepository.update(execution)
        val executionId = execution.id

        // Drive the lock listener through its real entry point so the
        // shutdown hook's heldExecutionIds() lookup includes this row.
        advisoryLockListener.beforeJob(execution)
        assertThat(advisoryLockListener.heldExecutionIds()).contains(executionId)

        val beforeAuditCount = jobLifecycleEventRepository.count()

        try {
            shutdownHook.markRunningJobsFailedOnShutdown()
        } finally {
            // afterJob releases the lock + closes the held connection,
            // even though the row has already been marked FAILED by the
            // hook. Skipping this leaks a pool connection across tests.
            advisoryLockListener.afterJob(execution)
        }

        val after = jobExplorer.getJobExecution(executionId)
        assertThat(after).isNotNull
        assertThat(after!!.status).isEqualTo(BatchStatus.FAILED)
        assertThat(after.exitStatus.exitDescription).contains("App shutdown")

        assertThat(jobLifecycleEventRepository.count()).isEqualTo(beforeAuditCount + 1)
        val audit = jobLifecycleEventRepository.findAll().firstOrNull { it.jobExecutionId == executionId }
        assertThat(audit).`as`("job_lifecycle_event row for executionId=$executionId").isNotNull
        assertThat(audit!!.eventType).isEqualTo(JobLifecycleEventType.SHUTDOWN)
        assertThat(audit.actionTaken).isEqualTo("stopped")
        assertThat(audit.jobName).isEqualTo(jobName)
        assertThat(audit.accountId).isEqualTo(accountId)
        assertThat(audit.eventTime).isNotNull
        assertThat(audit.details).contains("App shutdown")
    }

    @Test
    @DisplayName("no in-flight jobs → hook is a no-op, no audit rows written")
    fun noOpWhenNothingHeld() {
        // Snapshot: any rows already present from prior steps (BaseIT clears
        // before each test, but ApplicationReadyEvent reapings could land).
        val before = jobLifecycleEventRepository.count()
        // Sanity: nothing is in flight at this point.
        assertThat(advisoryLockListener.heldExecutionIds()).isEmpty()

        shutdownHook.markRunningJobsFailedOnShutdown()

        assertThat(jobLifecycleEventRepository.count()).isEqualTo(before)
    }
}
