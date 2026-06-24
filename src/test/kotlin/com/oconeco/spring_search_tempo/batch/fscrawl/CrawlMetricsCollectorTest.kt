package com.oconeco.spring_search_tempo.batch.fscrawl

import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import javax.sql.DataSource

/**
 * Unit tests for [CrawlMetricsCollector] (issue #149).
 *
 * The collector is mostly mechanics — start the sampler, watch peaks
 * grow, stop. We exercise it without firing the real scheduler by
 * using a non-Hikari DataSource (so `peakHikariActive` stays 0) and
 * by relying on the immediate first sample that [CrawlMetricsCollector.start]
 * runs synchronously. That keeps the test deterministic without
 * threading.
 */
@DisplayName("CrawlMetricsCollector (issue #149)")
class CrawlMetricsCollectorTest {

    @Test
    @DisplayName("start() takes an immediate sample so short crawls still get a heap reading")
    fun immediateSampleOnStart() {
        val collector = CrawlMetricsCollector(
            dataSource = mock(DataSource::class.java),
            sampleIntervalSeconds = 9999L // far in the future — the scheduled tick won't fire
        )
        collector.start()
        try {
            // Heap is sampled synchronously inside start(); JVM always has
            // some used heap, so peak must be positive.
            assertThat(collector.peakHeapBytes)
                .describedAs("start() should record an immediate heap sample")
                .isGreaterThan(0L)
        } finally {
            collector.stop()
        }
    }

    @Test
    @DisplayName("samples HikariCP active-connection peak when the DataSource is a HikariDataSource")
    fun samplesHikariActiveConnections() {
        val mxBean = mock(HikariPoolMXBean::class.java)
        `when`(mxBean.activeConnections).thenReturn(7)
        val hikari = mock(HikariDataSource::class.java)
        `when`(hikari.hikariPoolMXBean).thenReturn(mxBean)

        val collector = CrawlMetricsCollector(dataSource = hikari, sampleIntervalSeconds = 9999L)
        collector.start()
        try {
            assertThat(collector.peakHikariActive)
                .describedAs("first sample should pick up the mocked active count")
                .isEqualTo(7)
        } finally {
            collector.stop()
        }
    }

    @Test
    @DisplayName("non-Hikari DataSource leaves peakHikariActive at 0")
    fun nonHikariDataSourceIsZeroPool() {
        val collector = CrawlMetricsCollector(
            dataSource = mock(DataSource::class.java),
            sampleIntervalSeconds = 9999L
        )
        collector.start()
        collector.stop()
        assertThat(collector.peakHikariActive).isZero
    }

    @Test
    @DisplayName("start() is idempotent — calling twice doesn't reset peaks")
    fun startIsIdempotent() {
        val collector = CrawlMetricsCollector(
            dataSource = mock(DataSource::class.java),
            sampleIntervalSeconds = 9999L
        )
        collector.start()
        val first = collector.peakHeapBytes
        collector.start()
        try {
            assertThat(collector.peakHeapBytes).isGreaterThanOrEqualTo(first)
        } finally {
            collector.stop()
        }
    }
}
