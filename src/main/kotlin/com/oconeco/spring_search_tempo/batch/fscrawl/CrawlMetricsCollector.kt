package com.oconeco.spring_search_tempo.batch.fscrawl

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/**
 * Resource sampler for one crawl-config worth of work (issue #149).
 *
 * Polls [java.lang.management.MemoryMXBean] heap usage and HikariCP's
 * `activeConnections` every [sampleIntervalSeconds] on a small
 * background scheduler, tracking peaks. Designed to be cheap enough to
 * leave running for the entire crawl — both samples are O(1) reads off
 * already-maintained MBeans, no I/O.
 *
 * Lifecycle: construct → [start] → ... let the crawl run ... → [stop].
 * After [stop], the peak fields are stable. Calling [start] twice on
 * the same instance is a no-op (idempotent). The instance is single-use
 * — construct a fresh one per crawl.
 *
 * Hikari note: the orchestrator and the crawl share the application's
 * single primary DataSource, so the sample is "concurrent crawls" not
 * "crawls in this orchestrator." For the single-threaded sweep that
 * issue #149 instruments, that's fine — there's only one crawl in
 * flight at a time. When parallelism arrives, the parallel orchestrator
 * will need to attribute peaks to specific configs differently (likely
 * by stamping per-job per-thread counters rather than sampling).
 */
class CrawlMetricsCollector(
    private val dataSource: DataSource,
    private val sampleIntervalSeconds: Long = 2L,
    /** Optional injected scheduler — tests pass a deterministic one. */
    scheduler: ScheduledExecutorService? = null
) {

    companion object {
        private val log = LoggerFactory.getLogger(CrawlMetricsCollector::class.java)
    }

    private val ownsScheduler: Boolean = scheduler == null
    private val scheduler: ScheduledExecutorService = scheduler ?: Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "crawl-metrics-sampler").apply { isDaemon = true }
    }

    private val peakHeapBytesRef = AtomicLong(0)
    private val peakHikariActiveRef = AtomicLong(0)
    private var task: ScheduledFuture<*>? = null
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    val peakHeapBytes: Long get() = peakHeapBytesRef.get()
    val peakHikariActive: Int get() = peakHikariActiveRef.get().toInt()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Take an immediate sample so very short crawls still get a non-zero datapoint.
        sample()
        task = scheduler.scheduleAtFixedRate(
            ::sample,
            sampleIntervalSeconds,
            sampleIntervalSeconds,
            TimeUnit.SECONDS
        )
    }

    fun stop() {
        task?.cancel(false)
        task = null
        // Final sample after the workload — captures end-of-run peak.
        if (started.get()) sample()
        if (ownsScheduler) scheduler.shutdownNow()
    }

    private fun sample() {
        try {
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
            updateMax(peakHeapBytesRef, heap)

            val hikari = dataSource as? HikariDataSource
            val active = hikari?.hikariPoolMXBean?.activeConnections?.toLong() ?: 0L
            updateMax(peakHikariActiveRef, active)
        } catch (e: Exception) {
            log.debug("CrawlMetricsCollector sample failed: {}", e.message)
        }
    }

    private fun updateMax(ref: AtomicLong, candidate: Long) {
        while (true) {
            val current = ref.get()
            if (candidate <= current) return
            if (ref.compareAndSet(current, candidate)) return
        }
    }
}
