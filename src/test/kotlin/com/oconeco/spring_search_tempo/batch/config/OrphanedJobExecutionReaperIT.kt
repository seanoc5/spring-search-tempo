package com.oconeco.spring_search_tempo.batch.config

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
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
 * End-to-end orphan reaping (issue #64).
 *
 * Inserts a `STARTED` `BatchJobExecution` row via `JobRepository` (no
 * advisory lock acquired — simulating a JVM that crashed mid-job), runs
 * the reaper, and asserts the row is now `FAILED` with the reaper's
 * exit message.
 *
 * The reaper is invoked directly rather than via `ApplicationReadyEvent`
 * so the test is deterministic — the event fires at app boot before the
 * test executes, but it has nothing to reap then.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("OrphanedJobExecutionReaper (issue #64)")
class OrphanedJobExecutionReaperIT : BaseIT() {

    @Autowired
    lateinit var reaper: OrphanedJobExecutionReaper

    @Autowired
    lateinit var jobRepository: JobRepository

    @Autowired
    lateinit var jobExplorer: JobExplorer

    @Test
    @DisplayName("STARTED row with no advisory lock is marked FAILED on reap")
    fun reapsOrphanedExecution() {
        // Spring Batch requires unique JobInstance per (jobName, params).
        // Use System.nanoTime() to avoid collisions across test runs.
        val jobName = "orphanReaperIT_${System.nanoTime()}"
        val params = JobParametersBuilder()
            .addLong("uniq", System.nanoTime())
            .toJobParameters()

        val execution = jobRepository.createJobExecution(jobName, params)
        // Force STARTED — createJobExecution leaves status STARTING.
        execution.status = BatchStatus.STARTED
        jobRepository.update(execution)

        val executionId = execution.id

        // Sanity: JobExplorer should see this as a "running" execution.
        assertThat(jobExplorer.findRunningJobExecutions(jobName))
            .anyMatch { it.id == executionId }

        // No advisory lock was acquired in beforeJob (we bypassed the listener),
        // so the reaper's pg_try_advisory_lock will succeed → row is orphaned.
        reaper.reapOrphans()

        val after = jobExplorer.getJobExecution(executionId)
        assertThat(after).isNotNull
        assertThat(after!!.status).isEqualTo(BatchStatus.FAILED)
        assertThat(after.exitStatus.exitDescription)
            .contains("Orphan reaper")
        assertThat(after.endTime).isNotNull
    }

    @Test
    @DisplayName("STARTED row whose owner still holds the lock is NOT reaped")
    fun doesNotReapLiveExecution() {
        val jobName = "orphanReaperLiveIT_${System.nanoTime()}"
        val params = JobParametersBuilder()
            .addLong("uniq", System.nanoTime())
            .toJobParameters()

        val execution = jobRepository.createJobExecution(jobName, params)
        execution.status = BatchStatus.STARTED
        jobRepository.update(execution)
        val executionId = execution.id

        // Simulate a live owner: hold the advisory lock on a dedicated
        // connection for the duration of the reap call.
        val dataSource = applicationContext().getBean(javax.sql.DataSource::class.java)
        val ownerConn = dataSource.connection.apply { autoCommit = true }
        try {
            val acquired = ownerConn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
                ps.setLong(1, executionId)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            assertThat(acquired).`as`("owner must acquire its own lock").isTrue()

            reaper.reapOrphans()

            val after = jobExplorer.getJobExecution(executionId)!!
            assertThat(after.status)
                .`as`("live owner — must NOT be reaped")
                .isEqualTo(BatchStatus.STARTED)
        } finally {
            // Release and clean up so the test's STARTED row doesn't
            // pollute other tests' running-job queries.
            try {
                ownerConn.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                    ps.setLong(1, executionId)
                    ps.executeQuery().use { it.next() }
                }
            } finally {
                ownerConn.close()
            }
            // Now mark the row FAILED so we don't leak a fake "running" row.
            val cleanup = jobExplorer.getJobExecution(executionId)!!
            cleanup.status = BatchStatus.FAILED
            cleanup.setEndTime(java.time.LocalDateTime.now())
            jobRepository.update(cleanup)
        }
    }

    @Autowired
    private lateinit var ctx: org.springframework.context.ApplicationContext

    private fun applicationContext() = ctx
}
