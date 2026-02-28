package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.util.WebUtils
import com.oconeco.spring_search_tempo.web.service.DashboardService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.SortDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.data.domain.Pageable


@Controller
class HomeController(
    private val dashboardService: DashboardService,
    private val jobRunService: JobRunService,
    private val crawlConfigService: DatabaseCrawlConfigService,
    private val fileService: FSFileService,
    private val folderService: FSFolderService
) {

    @GetMapping("/")
    fun index(model: Model): String {
        // Get dashboard stats
        val stats = dashboardService.getStats()
        model.addAttribute("stats", stats)

        // Get recent job runs for activity feed (last 5)
        val recentJobRuns = jobRunService.findAll(
            null,
            PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "startTime"))
        ).content
        model.addAttribute("recentJobRuns", recentJobRuns)

        // Get crawl configs for the compact list (top 20)
        val crawlConfigs = crawlConfigService.findAll(null, PageRequest.of(0, 20, Sort.by("name")))
        model.addAttribute("crawlConfigs", crawlConfigs)

        // Initial folders list (all active configs, first page)
        val folders = folderService.findAll(null, PageRequest.of(0, 10, Sort.by("uri")), showSkipped = false)
        model.addAttribute("folders", folders)
        model.addAttribute("folderPaginationModel", WebUtils.getPaginationModel(folders))

        // Initial files list (all active configs, first page)
        val files = fileService.findAll(null, PageRequest.of(0, 10, Sort.by("uri")), showSkipped = false)
        model.addAttribute("files", files)
        model.addAttribute("filePaginationModel", WebUtils.getPaginationModel(files))

        return "home/index"
    }

    /**
     * HTMX endpoint to refresh dashboard statistics.
     * Note: The stats-content fragment needs all dashboard data, not just stats.
     */
    @GetMapping("/dashboard/stats")
    fun refreshStats(model: Model): String {
        // Get dashboard stats
        val stats = dashboardService.getStats()
        model.addAttribute("stats", stats)

        // Get recent job runs for activity feed (last 5)
        val recentJobRuns = jobRunService.findAll(
            null,
            PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "startTime"))
        ).content
        model.addAttribute("recentJobRuns", recentJobRuns)

        // Get crawl configs for the compact list (top 20)
        val crawlConfigs = crawlConfigService.findAll(null, PageRequest.of(0, 20, Sort.by("name")))
        model.addAttribute("crawlConfigs", crawlConfigs)

        // Initial folders list
        val folders = folderService.findAll(null, PageRequest.of(0, 10, Sort.by("uri")), showSkipped = false)
        model.addAttribute("folders", folders)

        // Initial files list
        val files = fileService.findAll(null, PageRequest.of(0, 10, Sort.by("uri")), showSkipped = false)
        model.addAttribute("files", files)

        return "home/index :: stats-content"
    }

    /**
     * HTMX endpoint for folders tab with filtering by crawl config(s).
     */
    @GetMapping("/dashboard/folders")
    fun dashboardFolders(
        @RequestParam(name = "configIds", required = false) configIds: List<Long>?,
        @RequestParam(name = "showSkipped", required = false, defaultValue = "false") showSkipped: Boolean,
        @SortDefault(sort = ["uri"]) @PageableDefault(size = 10) pageable: Pageable,
        model: Model
    ): String {
        val folders = if (configIds.isNullOrEmpty()) {
            // No filter - all folders
            folderService.findAll(null, pageable, showSkipped)
        } else if (configIds.size == 1) {
            // Single config filter
            folderService.findByCrawlConfigId(configIds.first(), pageable, showSkipped)
        } else {
            // Multiple configs - use findAll for now (could optimize with custom query)
            folderService.findAll(null, pageable, showSkipped)
        }

        model.addAttribute("folders", folders)
        model.addAttribute("folderPaginationModel", WebUtils.getPaginationModel(folders))
        model.addAttribute("showSkipped", showSkipped)
        model.addAttribute("configIds", configIds ?: emptyList<Long>())

        return "home/index :: folders-table"
    }

    /**
     * HTMX endpoint for files tab with filtering by crawl config(s).
     */
    @GetMapping("/dashboard/files")
    fun dashboardFiles(
        @RequestParam(name = "configIds", required = false) configIds: List<Long>?,
        @RequestParam(name = "showSkipped", required = false, defaultValue = "false") showSkipped: Boolean,
        @SortDefault(sort = ["uri"]) @PageableDefault(size = 10) pageable: Pageable,
        model: Model
    ): String {
        val files = if (configIds.isNullOrEmpty()) {
            // No filter - all files
            fileService.findAll(null, pageable, showSkipped)
        } else if (configIds.size == 1) {
            // Single config filter
            fileService.findByCrawlConfigId(configIds.first(), pageable, showSkipped)
        } else {
            // Multiple configs - use findAll for now
            fileService.findAll(null, pageable, showSkipped)
        }

        model.addAttribute("files", files)
        model.addAttribute("filePaginationModel", WebUtils.getPaginationModel(files))
        model.addAttribute("showSkipped", showSkipped)
        model.addAttribute("configIds", configIds ?: emptyList<Long>())

        return "home/index :: files-table"
    }

}
