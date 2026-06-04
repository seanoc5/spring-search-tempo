package com.oconeco.spring_search_tempo.batch.config

import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * JobExecutionListener that acquires a session-scoped PostgreSQL advisory lock
 * keyed by the Spring Batch `JobExecution.id` (issue #64).
 *
 * Why: the lock provides **hard evidence** that a JVM still owns a given
 * BatchJobExecution row. If the JVM dies (SIGKILL, OOM, panic, power loss),
 * the TCP connection drops, Postgres releases the lock automatically — no
 * application code involved. Another JVM (or our own boot reaper) can then
 * call `pg_try_advisory_lock(id)` and, if it acquires the lock, know with
 * certainty that the original holder is gone and the STARTED row is orphaned.
 *
 * Implementation notes:
 * - We hold a **dedicated** [Connection] open for the lifetime of the job.
 *   Returning it to the HikariCP pool would release the lock immediately
 *   (Postgres ties the lock to the underlying session). We never call
 *   [Connection.close] until [afterJob].
 * - One connection per running job. Sized for our workload (a handful of
 *   concurrent jobs); revisit if the system grows to hundreds.
 * - Lock failure in [beforeJob] is logged but does not abort the job —
 *   the heartbeat path is still in place as a backstop. The common cause
 *   of acquire-failure is the boot reaper racing the job; in that case
 *   the reaper would have already released the lock.
 */
@Component
class JobExecutionAdvisoryLockListener(
    private val dataSource: DataSource
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(JobExecutionAdvisoryLockListener::class.java)
    }

    private val heldConnections = ConcurrentHashMap<Long, Connection>()

    override fun beforeJob(jobExecution: JobExecution) {
        val executionId = jobExecution.id

        val connection: Connection = try {
            dataSource.connection
        } catch (e: Exception) {
            log.error("Could not obtain dedicated Connection for advisory lock on executionId={}: {}",
                executionId, e.message, e)
            return
        }

        try {
            connection.autoCommit = true
            val acquired = connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
                ps.setLong(1, executionId)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (!acquired) {
                log.warn("pg_try_advisory_lock({}) returned false in beforeJob — another session holds the lock. " +
                    "This is unusual since execution IDs are unique; the reaper may have just released it.",
                    executionId)
                connection.close()
                return
            }
            heldConnections[executionId] = connection
            log.debug("Acquired pg_advisory_lock({}) for job '{}'", executionId, jobExecution.jobInstance.jobName)
        } catch (e: Exception) {
            log.error("Failed to acquire advisory lock for executionId={}: {}", executionId, e.message, e)
            try {
                connection.close()
            } catch (closeError: Exception) {
                log.debug("Error closing connection after lock-acquire failure: {}", closeError.message)
            }
        }
    }

    override fun afterJob(jobExecution: JobExecution) {
        val executionId = jobExecution.id
        val connection = heldConnections.remove(executionId) ?: return

        try {
            connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                ps.setLong(1, executionId)
                ps.executeQuery().use { it.next() }
            }
            log.debug("Released pg_advisory_lock({}) for job '{}'", executionId, jobExecution.jobInstance.jobName)
        } catch (e: Exception) {
            log.warn("Failed to release advisory lock for executionId={} (connection close will release it anyway): {}",
                executionId, e.message)
        } finally {
            try {
                connection.close()
            } catch (e: Exception) {
                log.debug("Error closing lock-holding connection for executionId={}: {}", executionId, e.message)
            }
        }
    }

    /**
     * Execution IDs whose advisory lock we are currently holding.
     * Used by the graceful-shutdown hook to mark in-process STARTED jobs
     * as FAILED before the JVM exits.
     */
    fun heldExecutionIds(): Set<Long> = heldConnections.keys.toSet()
}
