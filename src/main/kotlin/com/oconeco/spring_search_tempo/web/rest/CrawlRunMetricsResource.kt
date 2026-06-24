package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.domain.CrawlRunMetrics
import com.oconeco.spring_search_tempo.base.repos.CrawlRunMetricsRepository
import org.springframework.data.domain.PageRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

/**
 * Admin REST surface for crawl-run metrics (issue #149).
 *
 * Returns the recent rows of [CrawlRunMetrics], optionally filtered to one
 * crawl config. Designed to be the JSON feed the parallel-crawl design
 * issue can reference for "wall-clock numbers from a single-crawl pass."
 */
@RestController
@RequestMapping("/api/admin/crawl-runs")
class CrawlRunMetricsResource(
    private val metricsRepository: CrawlRunMetricsRepository
) {

    @GetMapping
    fun list(
        @RequestParam(name = "config_id", required = false) configId: Long?,
        @RequestParam(name = "limit", required = false, defaultValue = "20") rawLimit: Int
    ): List<CrawlRunMetricsResponse> {
        val limit = rawLimit.coerceIn(1, 500)
        val page = PageRequest.of(0, limit)
        val rows = if (configId != null) {
            metricsRepository.findByCrawlConfigIdOrderByStartedAtDesc(configId, page).content
        } else {
            metricsRepository.findAllByOrderByStartedAtDesc(page).content
        }
        return rows.map(CrawlRunMetricsResponse::from)
    }
}

data class CrawlRunMetricsResponse(
    val id: Long?,
    val crawlConfigId: Long,
    val crawlConfigName: String?,
    val orchestratorOutcomeId: Long?,
    val jobExecutionId: String?,
    val jobRunId: Long?,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    val durationMs: Long?,
    val filesVisited: Long,
    val filesIndexed: Long,
    val filesSkipped: Long,
    val filesLevelSkip: Long,
    val filesLevelLocate: Long,
    val filesLevelIndex: Long,
    val filesLevelAnalyze: Long,
    val bytesRead: Long,
    val peakHeapBytes: Long?,
    val peakHikariActive: Int?,
    val tikaFailures: Long,
    val runStatus: String?,
    val errorMessage: String?
) {
    companion object {
        fun from(m: CrawlRunMetrics) = CrawlRunMetricsResponse(
            id = m.id,
            crawlConfigId = m.crawlConfigId,
            crawlConfigName = m.crawlConfigName,
            orchestratorOutcomeId = m.orchestratorOutcome?.id,
            jobExecutionId = m.jobExecutionId,
            jobRunId = m.jobRunId,
            startedAt = m.startedAt,
            finishedAt = m.finishedAt,
            durationMs = m.durationMs,
            filesVisited = m.filesVisited,
            filesIndexed = m.filesIndexed,
            filesSkipped = m.filesSkipped,
            filesLevelSkip = m.filesLevelSkip,
            filesLevelLocate = m.filesLevelLocate,
            filesLevelIndex = m.filesLevelIndex,
            filesLevelAnalyze = m.filesLevelAnalyze,
            bytesRead = m.bytesRead,
            peakHeapBytes = m.peakHeapBytes,
            peakHikariActive = m.peakHikariActive,
            tikaFailures = m.tikaFailures,
            runStatus = m.runStatus,
            errorMessage = m.errorMessage
        )
    }
}
