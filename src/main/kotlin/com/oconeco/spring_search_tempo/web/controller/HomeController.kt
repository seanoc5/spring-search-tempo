package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.model.JobRunDTO
import com.oconeco.spring_search_tempo.base.util.WebUtils
import com.oconeco.spring_search_tempo.web.model.FolderRowMetrics
import com.oconeco.spring_search_tempo.web.service.DashboardService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.SortDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.util.StopWatch
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
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/")
    fun index(model: Model): String {
        val stopWatch = StopWatch("Dashboard")

        stopWatch.start("getStats")
        model.addAttribute("stats", dashboardService.getStats())
        stopWatch.stop()

        stopWatch.start("populateDashboardContent")
        populateDashboardContent(model, stopWatch)
        stopWatch.stop()

        log.info("Dashboard render times:\n{}", stopWatch.prettyPrint())
        model.addAttribute("renderTimeMs", stopWatch.totalTimeMillis)

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

    /**
     * HTMX endpoint for crawl configs table with filtering and sorting.
     */
    @GetMapping("/dashboard/crawl-configs")
    fun dashboardCrawlConfigs(
        @RequestParam(name = "analysisStatus", required = false) analysisStatus: String?,
        @RequestParam(name = "enabled", required = false) enabled: Boolean?,
        @RequestParam(name = "sourceHost", required = false) sourceHost: String?,
        @RequestParam(name = "parallel", required = false) parallel: Boolean?,
        @RequestParam(name = "filter", required = false) filter: String?,
        @SortDefault(sort = ["name"]) @PageableDefault(size = 10) pageable: Pageable,
        model: Model
    ): String {
        populateCrawlConfigsModel(model, analysisStatus, enabled, sourceHost, parallel, filter, pageable)
        return "home/fragments/crawlConfigsTable :: crawl-configs-table"
    }

    private fun populateCrawlConfigsModel(
        model: Model,
        analysisStatus: String?,
        enabled: Boolean?,
        sourceHost: String?,
        parallel: Boolean?,
        filter: String?,
        pageable: Pageable
    ) {
        // Fetch all configs to enable grouping by sourceHost
        val allConfigs = crawlConfigService.findAll(filter, PageRequest.of(0, 1000, pageable.sort))

        // Apply in-memory filters
        val filtered = allConfigs.content.filter { config ->
            val matchesAnalysis = analysisStatus.isNullOrBlank() ||
                config.analysisStatus?.name == analysisStatus
            val matchesEnabled = enabled == null || config.enabled == enabled
            val matchesHost = sourceHost.isNullOrBlank() ||
                config.sourceHost?.contains(sourceHost, ignoreCase = true) == true
            val matchesParallel = parallel == null || config.parallel == parallel

            matchesAnalysis && matchesEnabled && matchesHost && matchesParallel
        }

        // Group by sourceHost (null -> "Unknown")
        val groupedConfigs = filtered.groupBy { it.sourceHost ?: "Unknown" }
            .toSortedMap()

        val crawlConfigMetrics = dashboardService.getCrawlConfigRowMetrics(filtered.mapNotNull { it.id })

        model.addAttribute("configsBySourceHost", groupedConfigs)
        model.addAttribute("crawlConfigMetrics", crawlConfigMetrics)

        // Pass current filter values back
        model.addAttribute("filterAnalysisStatus", analysisStatus)
        model.addAttribute("filterEnabled", enabled)
        model.addAttribute("filterSourceHost", sourceHost)
        model.addAttribute("filterParallel", parallel)
        model.addAttribute("filterText", filter)

        // Get distinct source hosts for filter dropdown
        val sourceHosts = allConfigs.content.mapNotNull { it.sourceHost }.distinct().sorted()
        model.addAttribute("sourceHosts", sourceHosts)
    }

    /**
     * HTMX endpoint for recent activity (job runs) table with filtering and sorting.
     */
    @GetMapping("/dashboard/activity")
    fun dashboardActivity(
        @RequestParam(name = "status", required = false) status: String?,
        @RequestParam(name = "jobName", required = false) jobName: String?,
        @RequestParam(name = "filter", required = false) filter: String?,
        @SortDefault(sort = ["startTime"], direction = Sort.Direction.DESC)
        @PageableDefault(size = 10) pageable: Pageable,
        model: Model
    ): String {
        val jobRuns = jobRunService.findAll(filter, pageable)

        // Apply in-memory filters for status
        val filtered = jobRuns.content.filter { job ->
            val matchesStatus = status.isNullOrBlank() ||
                job.runStatus?.name == status
            val matchesJobName = jobName.isNullOrBlank() ||
                job.jobName?.contains(jobName, ignoreCase = true) == true

            matchesStatus && matchesJobName
        }

        val filteredPage = PageImpl(filtered, pageable, filtered.size.toLong())

        model.addAttribute("recentJobRuns", filteredPage)
        model.addAttribute("activityPagination", WebUtils.getPaginationModel(filteredPage))

        // Pass current filter values back
        model.addAttribute("filterStatus", status)
        model.addAttribute("filterJobName", jobName)
        model.addAttribute("filterText", filter)

        // Get distinct job names for filter dropdown
        val allJobs = jobRunService.findAll(null, PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "startTime")))
        val jobNames = allJobs.content.mapNotNull { it.jobName }.distinct().sorted()
        model.addAttribute("jobNames", jobNames)

        return "home/fragments/activityTable :: activity-table"
    }

    private fun populateDashboardContent(model: Model, stopWatch: StopWatch? = null) {
        fun <T> timed(name: String, block: () -> T): T {
            stopWatch?.start(name)
            return block().also { stopWatch?.stop() }
        }

        // Recent activity (paginated)
        val recentJobRuns = timed("jobRuns.findAll") {
            jobRunService.findAll(null, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "startTime")))
        }
        model.addAttribute("recentJobRuns", recentJobRuns)
        model.addAttribute("activityPagination", WebUtils.getPaginationModel(recentJobRuns))

        // Get distinct job names for activity filter
        val allJobs = timed("jobRuns.findAll(1000)") {
            jobRunService.findAll(null, PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "startTime")))
        }
        model.addAttribute("jobNames", allJobs.content.mapNotNull { it.jobName }.distinct().sorted())

        // Crawl configs (grouped by sourceHost)
        timed("crawlConfigs") {
            populateCrawlConfigsModel(
                model,
                analysisStatus = null,
                enabled = null,
                sourceHost = null,
                parallel = null,
                filter = null,
                pageable = PageRequest.of(0, 1000, Sort.by("name"))
            )
        }

        val folders = timed("folders.findAll") {
            folderService.findAll(null, PageRequest.of(0, DEFAULT_WIDGET_PAGE_SIZE, Sort.by("uri")), showSkipped = false)
        }
        model.addAttribute("folders", folders)
        model.addAttribute("folderPaginationModel", WebUtils.getPaginationModel(folders))

        val folderMetrics = timed("folders.metrics") {
            dashboardService.getFolderRowMetrics(folders.content.mapNotNull { it.id })
        }
        model.addAttribute("folderMetricsById", folderMetrics)

        val files = timed("files.findAll") {
            fileService.findAll(null, PageRequest.of(0, DEFAULT_WIDGET_PAGE_SIZE, Sort.by("uri")), showSkipped = false)
        }
        model.addAttribute("files", files)
        model.addAttribute("filePaginationModel", WebUtils.getPaginationModel(files))

        val fileFragmentCounts = timed("files.fragmentCounts") {
            dashboardService.getFileFragmentCounts(files.content.mapNotNull { it.id })
        }
        model.addAttribute("fileFragmentCounts", fileFragmentCounts)
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
