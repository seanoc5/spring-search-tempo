package com.oconeco.spring_search_tempo.batch.config

import com.oconeco.spring_search_tempo.base.service.BatchAdminService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * On `ApplicationReadyEvent`, sweep `BatchJobExecution` rows still marked
 * `STARTED` (or other "running" statuses) and reap any whose owning JVM is
 * gone (issue #64).
 *
 * The test for "owner is gone" is the PostgreSQL session-scoped advisory
 * lock acquired by [JobExecutionAdvisoryLockListener] in `beforeJob`.
 * If `pg_try_advisory_lock(executionId)` succeeds, no other session holds
 * the lock — therefore the JVM that started the row is gone and the row
 * is orphaned. We release the lock immediately and mark the row FAILED.
 *
 * Why "hard evidence" beats heartbeat staleness:
 *   - No time-threshold tuning. A genuinely slow chunk (Tika on a big
 *     PDF, large IMAP fetch) cannot trigger a false positive.
 *   - No wait. Truth is available the instant the new JVM boots,
 *     not 2/15/60 minutes later when the heartbeat threshold fires.
 *
 * Heartbeat staleness still runs as defense-in-depth for in-process hangs
 * (wedged IMAP socket inside a live JVM). See `JobRunService.findStaleJobRuns`.
 *
 * Cross-JVM note: advisory locks DO work cross-JVM if all instances share
 * the same Postgres. Multi-JVM job coordination has additional design
 * questions (out of scope for #64).
 */
@Component
class OrphanedJobExecutionReaper(
    private val dataSource: DataSource,
    private val jobExplorer: JobExplorer,
    private val batchAdminService: BatchAdminService
) {

    companion object {
        private val log = LoggerFactory.getLogger(OrphanedJobExecutionReaper::class.java)
    }

    /**
     * Run after the application is fully started. Lower order than typical
     * autostarted-job listeners so any orphans from a prior JVM are reaped
     * before new jobs try to launch.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Order(0)
    fun reapOrphans() {
        val runningExecutions = jobExplorer.jobNames
            .flatMap { jobExplorer.findRunningJobExecutions(it) }

        if (runningExecutions.isEmpty()) {
            log.debug("Orphan reaper: no running BatchJobExecution rows to inspect")
            return
        }

        log.info("Orphan reaper: inspecting {} running BatchJobExecution row(s)", runningExecutions.size)

        var reaped = 0
        for (execution in runningExecutions) {
            val executionId = execution.id
            try {
                if (tryAcquireAndReleaseAdvisoryLock(executionId)) {
                    val jobName = execution.jobInstance.jobName
                    val startTime = execution.startTime
                    val reason = "Orphan reaper: advisory lock acquired — original JVM is gone " +
                        "(jobName='$jobName', startTime=$startTime)"
                    if (batchAdminService.markJobAsFailed(executionId, reason)) {
                        log.warn(
                            "Reaped orphaned BatchJobExecution executionId={} jobName='{}' startedAt={}",
                            executionId, jobName, startTime
                        )
                        reaped++
                    }
                } else {
                    log.debug(
                        "Skipping executionId={} — advisory lock held by another session (live owner or another reaper run)",
                        executionId
                    )
                }
            } catch (e: Exception) {
                log.warn("Orphan reaper failed for executionId={}: {}", executionId, e.message, e)
            }
        }

        if (reaped > 0) {
            log.warn(
                "Orphan reaper: marked {} of {} STARTED BatchJobExecution row(s) FAILED",
                reaped, runningExecutions.size
            )
        } else {
            log.info("Orphan reaper: no orphans found among {} running row(s)", runningExecutions.size)
        }
    }

    /**
     * Tries to acquire `pg_try_advisory_lock(executionId)` on a fresh
     * connection. If acquired, releases it immediately and returns `true`.
     * Returns `false` if another session holds the lock.
     */
    private fun tryAcquireAndReleaseAdvisoryLock(executionId: Long): Boolean {
        dataSource.connection.use { conn ->
            conn.autoCommit = true
            val acquired = conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
                ps.setLong(1, executionId)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (acquired) {
                conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                    ps.setLong(1, executionId)
                    ps.executeQuery().use { it.next() }
                }
            }
            return acquired
        }
    }
}
