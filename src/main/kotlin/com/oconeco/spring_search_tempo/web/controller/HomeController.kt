package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.util.WebUtils
import com.oconeco.spring_search_tempo.web.model.FolderRowMetrics
import com.oconeco.spring_search_tempo.web.service.DashboardService
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.SortDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.util.UriComponentsBuilder

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
        model.addAttribute("stats", dashboardService.getStats())
        populateDashboardContent(model)
        return "home/index"
    }

    /**
     * HTMX endpoint to refresh dashboard statistics.
     * Note: The stats-content fragment needs all dashboard data, not just stats.
     */
    @GetMapping("/dashboard/stats")
    fun refreshStats(model: Model): String {
        model.addAttribute("stats", dashboardService.getStats())
        populateDashboardContent(model)
        return "home/index :: stats-content"
    }

    /**
     * Open folder detail if an exact folder URI exists; otherwise go to filtered folder list.
     */
    @GetMapping("/dashboard/open-folder")
    fun openFolderByUri(@RequestParam("uri") uri: String): String {
        val exactMatch = folderService.findAll(
            uri,
            PageRequest.of(0, 20, Sort.by("uri")),
            showSkipped = true
        ).content.firstOrNull { it.uri == uri }

        if (exactMatch?.id != null) {
            return "redirect:/fSFolders/${exactMatch.id}"
        }

        val listUrl = UriComponentsBuilder.fromPath("/fSFolders")
            .queryParam("filter", uri)
            .build()
            .encode()
            .toUriString()

        return "redirect:$listUrl"
    }

    /**
     * HTMX endpoint for folders tab with filtering by crawl config(s).
     */
    @GetMapping("/dashboard/folders")
    fun dashboardFolders(
        @RequestParam(name = "configIds", required = false) configIds: List<Long>?,
        @RequestParam(name = "showSkipped", required = false, defaultValue = "false") showSkipped: Boolean,
        @SortDefault(sort = ["uri"]) @PageableDefault(size = DEFAULT_WIDGET_PAGE_SIZE) pageable: Pageable,
        model: Model
    ): String {
        val sortOrder = pageable.sort.firstOrNull()
        val queryPageable = toQueryPageableForFolders(pageable, sortOrder)

        val folders = if (configIds.isNullOrEmpty()) {
            folderService.findAll(null, queryPageable, showSkipped)
        } else if (configIds.size == 1) {
            folderService.findByCrawlConfigId(configIds.first(), queryPageable, showSkipped)
        } else {
            folderService.findAll(null, queryPageable, showSkipped)
        }

        val folderMetrics = dashboardService.getFolderRowMetrics(folders.content.mapNotNull { it.id })
        val sortedFolders = sortFoldersByComputedMetric(folders, pageable, sortOrder, folderMetrics)

        model.addAttribute("folders", sortedFolders)
        model.addAttribute("folderMetricsById", folderMetrics)
        model.addAttribute("folderPaginationModel", WebUtils.getPaginationModel(sortedFolders))
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
        @SortDefault(sort = ["uri"]) @PageableDefault(size = DEFAULT_WIDGET_PAGE_SIZE) pageable: Pageable,
        model: Model
    ): String {
        val sortOrder = pageable.sort.firstOrNull()
        val queryPageable = toQueryPageableForFiles(pageable, sortOrder)

        val files = if (configIds.isNullOrEmpty()) {
            fileService.findAll(null, queryPageable, showSkipped)
        } else if (configIds.size == 1) {
            fileService.findByCrawlConfigId(configIds.first(), queryPageable, showSkipped)
        } else {
            fileService.findAll(null, queryPageable, showSkipped)
        }

        val fragmentCounts = dashboardService.getFileFragmentCounts(files.content.mapNotNull { it.id })
        val sortedFiles = sortFilesByComputedMetric(files, pageable, sortOrder, fragmentCounts)

        model.addAttribute("files", sortedFiles)
        model.addAttribute("fileFragmentCounts", fragmentCounts)
        model.addAttribute("filePaginationModel", WebUtils.getPaginationModel(sortedFiles))
        model.addAttribute("showSkipped", showSkipped)
        model.addAttribute("configIds", configIds ?: emptyList<Long>())

        return "home/index :: files-table"
    }

    private fun populateDashboardContent(model: Model) {
        val recentJobRuns = jobRunService.findAll(
            null,
            PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "startTime"))
        ).content
        model.addAttribute("recentJobRuns", recentJobRuns)

        val crawlConfigs = crawlConfigService.findAll(null, PageRequest.of(0, 20, Sort.by("name")))
        model.addAttribute("crawlConfigs", crawlConfigs)

        val crawlConfigMetrics = dashboardService.getCrawlConfigRowMetrics(crawlConfigs.content.mapNotNull { it.id })
        model.addAttribute("crawlConfigMetrics", crawlConfigMetrics)

        val folders = folderService.findAll(
            null,
            PageRequest.of(0, DEFAULT_WIDGET_PAGE_SIZE, Sort.by("uri")),
            showSkipped = false
        )
        model.addAttribute("folders", folders)
        model.addAttribute("folderPaginationModel", WebUtils.getPaginationModel(folders))
        model.addAttribute("folderMetricsById", dashboardService.getFolderRowMetrics(folders.content.mapNotNull { it.id }))

        val files = fileService.findAll(
            null,
            PageRequest.of(0, DEFAULT_WIDGET_PAGE_SIZE, Sort.by("uri")),
            showSkipped = false
        )
        model.addAttribute("files", files)
        model.addAttribute("filePaginationModel", WebUtils.getPaginationModel(files))
        model.addAttribute("fileFragmentCounts", dashboardService.getFileFragmentCounts(files.content.mapNotNull { it.id }))
    }

    private fun toQueryPageableForFolders(pageable: Pageable, sortOrder: Sort.Order?): Pageable {
        if (sortOrder == null || sortOrder.property !in CUSTOM_FOLDER_SORT_FIELDS) {
            return pageable
        }
        return PageRequest.of(pageable.pageNumber, pageable.pageSize, Sort.by("uri"))
    }

    private fun toQueryPageableForFiles(pageable: Pageable, sortOrder: Sort.Order?): Pageable {
        if (sortOrder == null || sortOrder.property !in CUSTOM_FILE_SORT_FIELDS) {
            return pageable
        }
        return PageRequest.of(pageable.pageNumber, pageable.pageSize, Sort.by("uri"))
    }

    private fun sortFoldersByComputedMetric(
        folders: Page<FSFolderDTO>,
        pageable: Pageable,
        sortOrder: Sort.Order?,
        folderMetrics: Map<Long, FolderRowMetrics>
    ): Page<FSFolderDTO> {
        if (sortOrder == null || sortOrder.property !in CUSTOM_FOLDER_SORT_FIELDS || folders.content.isEmpty()) {
            return folders
        }

        val comparator = compareBy<FSFolderDTO> { folder ->
            val metrics = folder.id?.let { folderMetrics[it] }
            when (sortOrder.property) {
                "directFolderCount" -> metrics?.directFolderCount ?: 0L
                "recursiveFolderCount" -> metrics?.recursiveFolderCount ?: 0L
                "directFileCount" -> metrics?.directFileCount ?: 0L
                "recursiveFileCount" -> metrics?.recursiveFileCount ?: 0L
                "totalFileSize" -> metrics?.totalFileSize ?: 0L
                else -> 0L
            }
        }.thenBy { it.uri ?: "" }

        val sorted = if (sortOrder.isDescending) {
            folders.content.sortedWith(comparator.reversed())
        } else {
            folders.content.sortedWith(comparator)
        }

        return PageImpl(sorted, pageable, folders.totalElements)
    }

    private fun sortFilesByComputedMetric(
        files: Page<FSFileDTO>,
        pageable: Pageable,
        sortOrder: Sort.Order?,
        fragmentCounts: Map<Long, Long>
    ): Page<FSFileDTO> {
        if (sortOrder == null || sortOrder.property !in CUSTOM_FILE_SORT_FIELDS || files.content.isEmpty()) {
            return files
        }

        val comparator = compareBy<FSFileDTO> { file ->
            when (sortOrder.property) {
                "fragmentCount" -> file.id?.let { fragmentCounts[it] } ?: 0L
                else -> 0L
            }
        }.thenBy { it.uri ?: "" }

        val sorted = if (sortOrder.isDescending) {
            files.content.sortedWith(comparator.reversed())
        } else {
            files.content.sortedWith(comparator)
        }

        return PageImpl(sorted, pageable, files.totalElements)
    }

    companion object {
        private const val DEFAULT_WIDGET_PAGE_SIZE = 10
        private val CUSTOM_FOLDER_SORT_FIELDS = setOf(
            "directFolderCount",
            "recursiveFolderCount",
            "directFileCount",
            "recursiveFileCount",
            "totalFileSize"
        )
        private val CUSTOM_FILE_SORT_FIELDS = setOf("fragmentCount")
    }
}
