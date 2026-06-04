package com.oconeco.spring_search_tempo.batch.config

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * Hard-evidence liveness via PostgreSQL session-scoped advisory locks (issue #64).
 *
 * The test does not run a real Spring Batch job — it exercises the lock
 * primitive that powers the listener directly:
 *
 *   - Acquire `pg_advisory_lock(K)` on connection A.
 *   - Assert `pg_try_advisory_lock(K)` on connection B returns `false`
 *     (lock is held by a different session).
 *   - Close connection A.
 *   - Assert `pg_try_advisory_lock(K)` on connection B now returns `true`
 *     (Postgres released the lock when the TCP session died — no
 *     application code involved).
 *
 * This is the same mechanism [JobExecutionAdvisoryLockListener] relies on
 * to detect JVM death and [OrphanedJobExecutionReaper] uses to prove that
 * a `STARTED` BatchJobExecution row is orphaned.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("Advisory lock liveness primitive (issue #64)")
class JobExecutionAdvisoryLockListenerIT : BaseIT() {

    @Autowired
    lateinit var dataSource: DataSource

    private val openConnections = mutableListOf<Connection>()

    @AfterEach
    fun closeAll() {
        openConnections.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        openConnections.clear()
    }

    @Test
    @DisplayName("connection B sees lock as held until connection A closes (issue #64)")
    fun advisoryLockHeldUntilOwnerSessionDies() {
        val lockKey = uniqueLockKey()

        val connA = newDedicatedConnection()
        val connB = newDedicatedConnection()

        // A acquires the lock.
        assertThat(tryAcquireAdvisoryLock(connA, lockKey)).isTrue()

        // B cannot acquire it.
        assertThat(tryAcquireAdvisoryLock(connB, lockKey))
            .`as`("B should see lock held by A")
            .isFalse()

        // Simulate JVM death of A: close the connection without calling
        // pg_advisory_unlock. Postgres releases the session-scoped lock.
        connA.close()
        openConnections.remove(connA)

        // B can now acquire it — hard evidence A is gone.
        assertThat(tryAcquireAdvisoryLock(connB, lockKey))
            .`as`("B should see lock freed after A closed")
            .isTrue()

        // Clean up: release on B.
        releaseAdvisoryLock(connB, lockKey)
    }

    @Test
    @DisplayName("explicit pg_advisory_unlock on same session frees the lock")
    fun explicitUnlockFreesLock() {
        val lockKey = uniqueLockKey()
        val conn = newDedicatedConnection()

        assertThat(tryAcquireAdvisoryLock(conn, lockKey)).isTrue()
        releaseAdvisoryLock(conn, lockKey)

        // Same session can re-acquire after unlock.
        assertThat(tryAcquireAdvisoryLock(conn, lockKey)).isTrue()
        releaseAdvisoryLock(conn, lockKey)
    }

    /**
     * Open a *direct* JDBC connection to Postgres (not pooled). HikariCP's
     * `Connection.close()` returns the connection to the pool rather than
     * closing the underlying TCP session — so it doesn't simulate JVM death.
     * To prove the lock survives only as long as the session, the test
     * needs real driver-level connections that we can really close.
     */
    private fun newDedicatedConnection(): Connection {
        val hikari = dataSource as HikariDataSource
        val c = DriverManager.getConnection(hikari.jdbcUrl, hikari.username, hikari.password)
        c.autoCommit = true
        openConnections += c
        return c
    }

    private fun tryAcquireAdvisoryLock(conn: Connection, key: Long): Boolean {
        conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
            ps.setLong(1, key)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getBoolean(1)
            }
        }
    }

    private fun releaseAdvisoryLock(conn: Connection, key: Long) {
        conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
            ps.setLong(1, key)
            ps.executeQuery().use { it.next() }
        }
    }

    /**
     * Use a key far above any real `BatchJobExecution.id` so the test never
     * collides with real lock keys held by app code running in parallel.
     */
    private fun uniqueLockKey(): Long =
        Long.MAX_VALUE / 2 + System.nanoTime() % 1_000_000
}
