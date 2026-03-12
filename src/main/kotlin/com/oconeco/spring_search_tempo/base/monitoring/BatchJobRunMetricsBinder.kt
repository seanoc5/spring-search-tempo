package com.oconeco.spring_search_tempo.base.monitoring

import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Exposes job-run operational gauges for Prometheus/Grafana.
 */
@Component
class BatchJobRunMetricsBinder(
    meterRegistry: MeterRegistry,
    private val jobRunRepository: JobRunRepository,
    @Value("\${app.monitoring.batch.stale-threshold-minutes:2}")
    private val staleThresholdMinutes: Long
) {
    companion object {
        private val log = LoggerFactory.getLogger(BatchJobRunMetricsBinder::class.java)
    }

    init {
        RunStatus.entries.forEach { status ->
            Gauge.builder("tempo.batch.jobrun.status") {
                safeCountByStatus(status)
            }
                .description("Current JobRun count by status")
                .tag("status", status.name)
                .register(meterRegistry)
        }

        Gauge.builder("tempo.batch.jobrun.total") {
            safeTotalCount()
        }
            .description("Total JobRun rows")
            .register(meterRegistry)

        Gauge.builder("tempo.batch.jobrun.stale.running") {
            safeStaleRunningCount()
        }
            .description("Running JobRuns that are stale by heartbeat threshold")
            .register(meterRegistry)
    }

    private fun safeCountByStatus(status: RunStatus): Double = try {
        jobRunRepository.countByRunStatus(status).toDouble()
    } catch (e: Exception) {
        log.debug("Failed to read JobRun status gauge for {}: {}", status, e.message)
        0.0
    }

    private fun safeTotalCount(): Double = try {
        jobRunRepository.count().toDouble()
    } catch (e: Exception) {
        log.debug("Failed to read JobRun total gauge: {}", e.message)
        0.0
    }

    private fun safeStaleRunningCount(): Double = try {
        val threshold = OffsetDateTime.now().minusMinutes(staleThresholdMinutes.coerceAtLeast(1))
        jobRunRepository.countStaleRunningJobs(threshold).toDouble()
    } catch (e: Exception) {
        log.debug("Failed to read stale JobRun gauge: {}", e.message)
        0.0
    }
}
