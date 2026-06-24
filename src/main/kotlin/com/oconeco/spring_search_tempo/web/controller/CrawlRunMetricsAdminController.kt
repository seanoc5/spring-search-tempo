package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.CrawlRunMetricsRepository
import com.oconeco.spring_search_tempo.base.util.WebUtils
import jakarta.servlet.http.HttpServletResponse
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.SortDefault
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Admin UI for crawl-run metrics (issue #149).
 *
 *  - `/admin/crawl-runs` lists the most recent metrics rows with a config
 *    filter dropdown, sortable headers, and standard pagination.
 *  - `/admin/crawl-runs/export.csv` streams the same query as CSV — the
 *    artifact the parallel-crawl design issue asks for ("attach the
 *    resulting metrics table to the PR").
 */
@Controller
@RequestMapping("/admin/crawl-runs")
class CrawlRunMetricsAdminController(
    private val metricsRepository: CrawlRunMetricsRepository,
    private val crawlConfigRepository: CrawlConfigRepository
) {

    @GetMapping
    fun list(
        @RequestParam(name = "configId", required = false) configId: Long?,
        @SortDefault(sort = ["startedAt"], direction = org.springframework.data.domain.Sort.Direction.DESC)
        @PageableDefault(size = 25) pageable: Pageable,
        model: Model
    ): String {
        val page = if (configId != null) {
            metricsRepository.findByCrawlConfigId(configId, pageable)
        } else {
            metricsRepository.findAll(pageable)
        }
        model.addAttribute("metrics", page)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(page))
        model.addAttribute("configId", configId)
        model.addAttribute("configs", crawlConfigRepository.findAll())
        return "admin/crawl-runs/list"
    }

    @GetMapping("/export.csv", produces = ["text/csv"])
    fun exportCsv(
        @RequestParam(name = "configId", required = false) configId: Long?,
        @RequestParam(name = "limit", required = false, defaultValue = "500") rawLimit: Int,
        response: HttpServletResponse
    ) {
        val limit = rawLimit.coerceIn(1, 5000)
        val pageable = org.springframework.data.domain.PageRequest.of(
            0, limit,
            org.springframework.data.domain.Sort.by("startedAt").descending()
        )
        val rows = if (configId != null) {
            metricsRepository.findByCrawlConfigIdOrderByStartedAtDesc(configId, pageable).content
        } else {
            metricsRepository.findAllByOrderByStartedAtDesc(pageable).content
        }

        response.contentType = MediaType.parseMediaType("text/csv").toString()
        response.setHeader(
            "Content-Disposition",
            "attachment; filename=\"crawl-run-metrics.csv\""
        )
        response.writer.use { w ->
            w.append("id,crawl_config_id,crawl_config_name,started_at,finished_at,duration_ms,")
            w.append("files_visited,files_indexed,files_skipped,")
            w.append("files_level_skip,files_level_locate,files_level_index,files_level_analyze,")
            w.append("bytes_read,peak_heap_bytes,peak_hikari_active,tika_failures,run_status\n")
            for (m in rows) {
                w.append(m.id?.toString().orEmpty()).append(",")
                w.append(m.crawlConfigId.toString()).append(",")
                w.append(csvEscape(m.crawlConfigName)).append(",")
                w.append(m.startedAt.toString()).append(",")
                w.append(m.finishedAt?.toString().orEmpty()).append(",")
                w.append(m.durationMs?.toString().orEmpty()).append(",")
                w.append(m.filesVisited.toString()).append(",")
                w.append(m.filesIndexed.toString()).append(",")
                w.append(m.filesSkipped.toString()).append(",")
                w.append(m.filesLevelSkip.toString()).append(",")
                w.append(m.filesLevelLocate.toString()).append(",")
                w.append(m.filesLevelIndex.toString()).append(",")
                w.append(m.filesLevelAnalyze.toString()).append(",")
                w.append(m.bytesRead.toString()).append(",")
                w.append(m.peakHeapBytes?.toString().orEmpty()).append(",")
                w.append(m.peakHikariActive?.toString().orEmpty()).append(",")
                w.append(m.tikaFailures.toString()).append(",")
                w.append(csvEscape(m.runStatus)).append("\n")
            }
        }
    }

    private fun csvEscape(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val needsQuote = value.contains(',') || value.contains('"') || value.contains('\n')
        if (!needsQuote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
