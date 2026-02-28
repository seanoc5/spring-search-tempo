package com.oconeco.spring_search_tempo.web.controller

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.base.service.CrawlDataCleanupService
import com.oconeco.spring_search_tempo.base.util.WebUtils
import jakarta.servlet.http.HttpServletRequest
import com.oconeco.spring_search_tempo.batch.fscrawl.CrawlCleanupListener
import com.oconeco.spring_search_tempo.batch.fscrawl.FsCrawlJobBuilder
import com.oconeco.spring_search_tempo.batch.fscrawl.JobRunTrackingListener
import jakarta.validation.Valid
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.SortDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Instant

@Controller
@RequestMapping("/crawlConfigs")
class CrawlConfigController(
    private val crawlConfigService: DatabaseCrawlConfigService,
    private val jobRunService: JobRunService,
    private val fileService: FSFileService,
    private val folderService: FSFolderService,
    private val jobLauncher: JobLauncher,
    private val jobBuilder: FsCrawlJobBuilder,
    private val configConverter: CrawlConfigConverter,
    private val cleanupService: CrawlDataCleanupService,
    private val objectMapper: ObjectMapper
) {

    @GetMapping
    fun list(
        @RequestParam(name = "filter", required = false) filter: String?,
        @SortDefault(sort = ["id"]) @PageableDefault(size = 20) pageable: Pageable,
        model: Model
    ): String {
        val crawlConfigs = crawlConfigService.findAll(filter, pageable)

        // Build file count map for each config (excludes SKIP by default)
        val fileCountsMap = crawlConfigs.content.associate { config ->
            config.id!! to fileService.countByCrawlConfigId(config.id!!, includeSkipped = false)
        }

        // Build folder count map for each config (excludes SKIP by default)
        val folderCountsMap = crawlConfigs.content.associate { config ->
            config.id!! to folderService.countByCrawlConfigId(config.id!!, includeSkipped = false)
        }

        // Build last run map for each config
        val lastRunMap = crawlConfigs.content.mapNotNull { config ->
            jobRunService.getLatestRunForConfig(config.id!!)?.let { config.id!! to it }
        }.toMap()

        model.addAttribute("crawlConfigs", crawlConfigs)
        model.addAttribute("fileCounts", fileCountsMap)
        model.addAttribute("folderCounts", folderCountsMap)
        model.addAttribute("lastRuns", lastRunMap)
        model.addAttribute("filter", filter)
        model.addAttribute("page", crawlConfigs)
        return "crawlConfig/list"
    }

    @GetMapping("/add")
    fun add(model: Model): String {
        model.addAttribute("crawlConfig", CrawlConfigDTO().apply {
            enabled = true
            maxDepth = 50
            followLinks = false
            parallel = false
        })
        model.addAttribute("startPathsText", "")
        model.addAttribute("folderPatternsSkipText", "")
        model.addAttribute("folderPatternsLocateText", "")
        model.addAttribute("folderPatternsIndexText", "")
        model.addAttribute("folderPatternsAnalyzeText", "")
        model.addAttribute("filePatternsSkipText", "")
        model.addAttribute("filePatternsLocateText", "")
        model.addAttribute("filePatternsIndexText", "")
        model.addAttribute("filePatternsAnalyzeText", "")
        return "crawlConfig/add"
    }

    @PostMapping("/add")
    fun add(
        @ModelAttribute("crawlConfig") @Valid crawlConfigDTO: CrawlConfigDTO,
        @RequestParam(name = "startPathsText", required = false) startPathsText: String?,
        @RequestParam(name = "folderPatternsSkipText", required = false) folderPatternsSkipText: String?,
        @RequestParam(name = "folderPatternsLocateText", required = false) folderPatternsLocateText: String?,
        @RequestParam(name = "folderPatternsIndexText", required = false) folderPatternsIndexText: String?,
        @RequestParam(name = "folderPatternsAnalyzeText", required = false) folderPatternsAnalyzeText: String?,
        @RequestParam(name = "filePatternsSkipText", required = false) filePatternsSkipText: String?,
        @RequestParam(name = "filePatternsLocateText", required = false) filePatternsLocateText: String?,
        @RequestParam(name = "filePatternsIndexText", required = false) filePatternsIndexText: String?,
        @RequestParam(name = "filePatternsAnalyzeText", required = false) filePatternsAnalyzeText: String?,
        bindingResult: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        // Convert textarea back to list
        crawlConfigDTO.startPaths = startPathsText
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        // Convert pattern text areas to JSON arrays
        crawlConfigDTO.folderPatternsSkip = textToJson(folderPatternsSkipText)
        crawlConfigDTO.folderPatternsLocate = textToJson(folderPatternsLocateText)
        crawlConfigDTO.folderPatternsIndex = textToJson(folderPatternsIndexText)
        crawlConfigDTO.folderPatternsAnalyze = textToJson(folderPatternsAnalyzeText)
        crawlConfigDTO.filePatternsSkip = textToJson(filePatternsSkipText)
        crawlConfigDTO.filePatternsLocate = textToJson(filePatternsLocateText)
        crawlConfigDTO.filePatternsIndex = textToJson(filePatternsIndexText)
        crawlConfigDTO.filePatternsAnalyze = textToJson(filePatternsAnalyzeText)

        // Generate URI from name if not provided
        if (crawlConfigDTO.uri.isNullOrBlank()) {
            crawlConfigDTO.uri = "crawl-config:${crawlConfigDTO.name?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-") ?: "unnamed"}"
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("startPathsText", startPathsText)
            model.addAttribute("folderPatternsSkipText", folderPatternsSkipText)
            model.addAttribute("folderPatternsLocateText", folderPatternsLocateText)
            model.addAttribute("folderPatternsIndexText", folderPatternsIndexText)
            model.addAttribute("folderPatternsAnalyzeText", folderPatternsAnalyzeText)
            model.addAttribute("filePatternsSkipText", filePatternsSkipText)
            model.addAttribute("filePatternsLocateText", filePatternsLocateText)
            model.addAttribute("filePatternsIndexText", filePatternsIndexText)
            model.addAttribute("filePatternsAnalyzeText", filePatternsAnalyzeText)
            return "crawlConfig/add"
        }

        try {
            val newId = crawlConfigService.create(crawlConfigDTO)
            redirectAttributes.addFlashAttribute("message",
                "Configuration created: ${crawlConfigDTO.displayLabel ?: crawlConfigDTO.name}")
            return "redirect:/crawlConfigs/$newId"
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error",
                "Failed to create configuration: ${e.message}")
            return "redirect:/crawlConfigs/add"
        }
    }

    @GetMapping("/{id}")
    fun view(
        @PathVariable(name = "id") id: Long,
        @PageableDefault(size = 20) pageable: Pageable,
        model: Model
    ): String {
        val crawlConfig = crawlConfigService.get(id)
        val jobRuns = jobRunService.findByCrawlConfigId(id, pageable)

        // Content summary counts (excludes SKIP by default)
        val totalFileCount = fileService.countByCrawlConfigId(id, includeSkipped = false)
        val totalFolderCount = folderService.countByCrawlConfigId(id, includeSkipped = false)

        // Per-job-run file counts for the table
        val jobRunFileCounts = jobRuns.content.associate { jobRun ->
            jobRun.id!! to fileService.countByJobRunId(jobRun.id!!)
        }
        val jobRunFolderCounts = jobRuns.content.associate { jobRun ->
            jobRun.id!! to folderService.countByJobRunId(jobRun.id!!)
        }

        model.addAttribute("crawlConfig", crawlConfig)
        model.addAttribute("jobRuns", jobRuns)
        model.addAttribute("page", jobRuns)
        model.addAttribute("totalFileCount", totalFileCount)
        model.addAttribute("totalFolderCount", totalFolderCount)
        model.addAttribute("jobRunFileCounts", jobRunFileCounts)
        model.addAttribute("jobRunFolderCounts", jobRunFolderCounts)
        return "crawlConfig/view"
    }

    @GetMapping("/{id}/edit")
    fun edit(@PathVariable(name = "id") id: Long, model: Model): String {
        val crawlConfig = crawlConfigService.get(id)
        // Convert startPaths list to newline-separated string for textarea
        val startPathsText = crawlConfig.startPaths?.joinToString("\n") ?: ""
        model.addAttribute("crawlConfig", crawlConfig)
        model.addAttribute("startPathsText", startPathsText)

        // Convert JSON pattern arrays to newline-separated text for textareas
        model.addAttribute("folderPatternsSkipText", jsonToText(crawlConfig.folderPatternsSkip))
        model.addAttribute("folderPatternsLocateText", jsonToText(crawlConfig.folderPatternsLocate))
        model.addAttribute("folderPatternsIndexText", jsonToText(crawlConfig.folderPatternsIndex))
        model.addAttribute("folderPatternsAnalyzeText", jsonToText(crawlConfig.folderPatternsAnalyze))
        model.addAttribute("filePatternsSkipText", jsonToText(crawlConfig.filePatternsSkip))
        model.addAttribute("filePatternsLocateText", jsonToText(crawlConfig.filePatternsLocate))
        model.addAttribute("filePatternsIndexText", jsonToText(crawlConfig.filePatternsIndex))
        model.addAttribute("filePatternsAnalyzeText", jsonToText(crawlConfig.filePatternsAnalyze))

        return "crawlConfig/edit"
    }

    @PostMapping("/{id}/edit")
    fun edit(
        @PathVariable(name = "id") id: Long,
        @ModelAttribute("crawlConfig") @Valid crawlConfigDTO: CrawlConfigDTO,
        @RequestParam(name = "startPathsText", required = false) startPathsText: String?,
        @RequestParam(name = "folderPatternsSkipText", required = false) folderPatternsSkipText: String?,
        @RequestParam(name = "folderPatternsLocateText", required = false) folderPatternsLocateText: String?,
        @RequestParam(name = "folderPatternsIndexText", required = false) folderPatternsIndexText: String?,
        @RequestParam(name = "folderPatternsAnalyzeText", required = false) folderPatternsAnalyzeText: String?,
        @RequestParam(name = "filePatternsSkipText", required = false) filePatternsSkipText: String?,
        @RequestParam(name = "filePatternsLocateText", required = false) filePatternsLocateText: String?,
        @RequestParam(name = "filePatternsIndexText", required = false) filePatternsIndexText: String?,
        @RequestParam(name = "filePatternsAnalyzeText", required = false) filePatternsAnalyzeText: String?,
        bindingResult: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        // Convert textarea back to list
        crawlConfigDTO.startPaths = startPathsText
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        // Convert pattern text areas to JSON arrays
        crawlConfigDTO.folderPatternsSkip = textToJson(folderPatternsSkipText)
        crawlConfigDTO.folderPatternsLocate = textToJson(folderPatternsLocateText)
        crawlConfigDTO.folderPatternsIndex = textToJson(folderPatternsIndexText)
        crawlConfigDTO.folderPatternsAnalyze = textToJson(folderPatternsAnalyzeText)
        crawlConfigDTO.filePatternsSkip = textToJson(filePatternsSkipText)
        crawlConfigDTO.filePatternsLocate = textToJson(filePatternsLocateText)
        crawlConfigDTO.filePatternsIndex = textToJson(filePatternsIndexText)
        crawlConfigDTO.filePatternsAnalyze = textToJson(filePatternsAnalyzeText)

        if (bindingResult.hasErrors()) {
            model.addAttribute("startPathsText", startPathsText)
            model.addAttribute("folderPatternsSkipText", folderPatternsSkipText)
            model.addAttribute("folderPatternsLocateText", folderPatternsLocateText)
            model.addAttribute("folderPatternsIndexText", folderPatternsIndexText)
            model.addAttribute("folderPatternsAnalyzeText", folderPatternsAnalyzeText)
            model.addAttribute("filePatternsSkipText", filePatternsSkipText)
            model.addAttribute("filePatternsLocateText", filePatternsLocateText)
            model.addAttribute("filePatternsIndexText", filePatternsIndexText)
            model.addAttribute("filePatternsAnalyzeText", filePatternsAnalyzeText)
            return "crawlConfig/edit"
        }

        try {
            crawlConfigService.update(id, crawlConfigDTO)
            redirectAttributes.addFlashAttribute("message",
                "Configuration updated: ${crawlConfigDTO.displayLabel ?: crawlConfigDTO.name}")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error",
                "Failed to update configuration: ${e.message}")
        }

        return "redirect:/crawlConfigs/$id"
    }

    @PostMapping("/{id}/run")
    fun runCrawl(
        @PathVariable(name = "id") id: Long,
        @RequestParam(name = "forceFullRecrawl", required = false, defaultValue = "false") forceFullRecrawl: Boolean,
        @RequestParam(name = "deleteExistingData", required = false, defaultValue = "false") deleteExistingData: Boolean,
        @RequestParam(name = "chunkProcessAll", required = false, defaultValue = "false") chunkProcessAll: Boolean,
        redirectAttributes: RedirectAttributes
    ): String {
        val crawlConfig = crawlConfigService.get(id)

        try {
            // Convert database config to CrawlDefinition
            val crawlDefinition = configConverter.toDefinition(crawlConfig)

            // Build the job dynamically from the crawl config
            // Pass crawl config ID and freshnessHours for recent crawl skip logic
            val job = jobBuilder.buildJob(
                crawl = crawlDefinition,
                forceFullRecrawl = forceFullRecrawl,
                crawlConfigId = id,
                freshnessHours = crawlConfig.freshnessHours,
                chunkProcessAll = chunkProcessAll
            )

            // Create job parameters with crawl config ID and timestamp for uniqueness
            val jobParams = JobParametersBuilder()
                .addString(JobRunTrackingListener.CRAWL_CONFIG_ID_KEY, id.toString())
                .addString(CrawlCleanupListener.DELETE_EXISTING_DATA_KEY, deleteExistingData.toString())
                .addLong("timestamp", Instant.now().toEpochMilli())
                .toJobParameters()

            // Launch the job asynchronously
            jobLauncher.run(job, jobParams)

            val options = mutableListOf<String>()
            if (forceFullRecrawl) options.add("force full recrawl")
            if (deleteExistingData) options.add("delete existing data")
            if (chunkProcessAll) options.add("chunk all files")
            val optionsText = if (options.isNotEmpty()) " (${options.joinToString(", ")})" else ""

            redirectAttributes.addFlashAttribute("message",
                "Crawl job started for configuration: ${crawlConfig.displayLabel ?: crawlConfig.name}$optionsText")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error",
                "Failed to start crawl: ${e.message}")
        }

        return "redirect:/crawlConfigs/$id"
    }

    @GetMapping("/jobRuns")
    fun listJobRuns(
        @RequestParam(name = "filter", required = false) filter: String?,
        @SortDefault(sort = ["startTime"], direction = Sort.Direction.DESC)
        @PageableDefault(size = 20) pageable: Pageable,
        model: Model
    ): String {
        val jobRuns = jobRunService.findAll(filter, pageable)
        model.addAttribute("jobRuns", jobRuns)
        model.addAttribute("filter", filter)
        model.addAttribute("page", jobRuns)
        return "crawlConfig/jobRuns"
    }

    @GetMapping("/{id}/files")
    fun browseFiles(
        @PathVariable(name = "id") id: Long,
        @RequestParam(name = "showSkipped", required = false, defaultValue = "false") showSkipped: Boolean,
        @RequestParam(name = "filter", required = false) filter: String?,
        @SortDefault(sort = ["uri"]) @PageableDefault(size = 50) pageable: Pageable,
        model: Model,
        request: HttpServletRequest
    ): String {
        val crawlConfig = crawlConfigService.get(id)
        val files = fileService.findByCrawlConfigId(id, pageable, showSkipped)

        model.addAttribute("crawlConfig", crawlConfig)
        model.addAttribute("files", files)
        model.addAttribute("page", files)
        model.addAttribute("showSkipped", showSkipped)
        model.addAttribute("filter", filter)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(files))

        // HX-Boosted requests expect full page; only return fragment for non-boosted HTMX
        val isHtmx = request.getHeader("HX-Request") == "true"
        val isBoosted = request.getHeader("HX-Boosted") == "true"
        return if (isHtmx && !isBoosted) {
            "crawlConfig/files :: table-content"
        } else {
            "crawlConfig/files"
        }
    }

    @PostMapping("/{id}/toggle-enabled")
    fun toggleEnabled(
        @PathVariable(name = "id") id: Long,
        model: Model
    ): String {
        val enabled = crawlConfigService.toggleEnabled(id)
        model.addAttribute("id", id)
        model.addAttribute("enabled", enabled)
        return "crawlConfig/fragments/enabledToggle"
    }

    @PostMapping("/{id}/delete-data")
    fun deleteData(
        @PathVariable(name = "id") id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val crawlConfig = crawlConfigService.get(id)
        val summary = cleanupService.deleteAllDataForCrawlConfig(id)

        if (summary.isEmpty) {
            redirectAttributes.addFlashAttribute("message", "No data to delete for '${crawlConfig.displayLabel ?: crawlConfig.name}'")
        } else {
            redirectAttributes.addFlashAttribute(
                "message",
                "Deleted ${summary.filesDeleted} files, ${summary.foldersDeleted} folders, and ${summary.chunksDeleted} chunks for '${crawlConfig.displayLabel ?: crawlConfig.name}'"
            )
        }
        return "redirect:/crawlConfigs/$id"
    }

    @GetMapping("/{id}/folders")
    fun browseFolders(
        @PathVariable(name = "id") id: Long,
        @RequestParam(name = "showSkipped", required = false, defaultValue = "false") showSkipped: Boolean,
        @RequestParam(name = "filter", required = false) filter: String?,
        @SortDefault(sort = ["uri"]) @PageableDefault(size = 50) pageable: Pageable,
        model: Model,
        request: HttpServletRequest
    ): String {
        val crawlConfig = crawlConfigService.get(id)
        val folders = folderService.findByCrawlConfigId(id, pageable, showSkipped)

        model.addAttribute("crawlConfig", crawlConfig)
        model.addAttribute("folders", folders)
        model.addAttribute("page", folders)
        model.addAttribute("showSkipped", showSkipped)
        model.addAttribute("filter", filter)
        model.addAttribute("paginationModel", WebUtils.getPaginationModel(folders))

        // HX-Boosted requests expect full page; only return fragment for non-boosted HTMX
        val isHtmx = request.getHeader("HX-Request") == "true"
        val isBoosted = request.getHeader("HX-Boosted") == "true"
        return if (isHtmx && !isBoosted) {
            "crawlConfig/folders :: table-content"
        } else {
            "crawlConfig/folders"
        }
    }

    /**
     * Handle GET requests to /run-selected (redirect to list).
     * This prevents the /{id} route from catching "run-selected" as an ID.
     */
    @GetMapping("/run-selected")
    fun runSelectedGet(redirectAttributes: RedirectAttributes): String {
        redirectAttributes.addFlashAttribute("error", "Please use the form to run selected configurations")
        return "redirect:/crawlConfigs"
    }

    /**
     * Run multiple crawl configurations.
     */
    @PostMapping("/run-selected")
    fun runSelectedCrawls(
        @RequestParam(name = "selectedIds") selectedIds: List<Long>,
        redirectAttributes: RedirectAttributes
    ): String {
        if (selectedIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "No configurations selected")
            return "redirect:/crawlConfigs"
        }

        val started = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (id in selectedIds) {
            try {
                val crawlConfig = crawlConfigService.get(id)
                if (!crawlConfig.enabled) {
                    errors.add("${crawlConfig.displayLabel ?: crawlConfig.name} (disabled)")
                    continue
                }

                val crawlDefinition = configConverter.toDefinition(crawlConfig)
                val job = jobBuilder.buildJob(
                    crawl = crawlDefinition,
                    forceFullRecrawl = false,
                    crawlConfigId = id,
                    freshnessHours = crawlConfig.freshnessHours
                )

                val jobParams = JobParametersBuilder()
                    .addString(JobRunTrackingListener.CRAWL_CONFIG_ID_KEY, id.toString())
                    .addString(CrawlCleanupListener.DELETE_EXISTING_DATA_KEY, "false")
                    .addLong("timestamp", Instant.now().toEpochMilli())
                    .toJobParameters()

                jobLauncher.run(job, jobParams)
                started.add(crawlConfig.displayLabel ?: crawlConfig.name ?: "Config #$id")
            } catch (e: Exception) {
                errors.add("Config #$id: ${e.message}")
            }
        }

        if (started.isNotEmpty()) {
            redirectAttributes.addFlashAttribute("message",
                "Started crawl jobs: ${started.joinToString(", ")}")
        }
        if (errors.isNotEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                "Failed to start: ${errors.joinToString(", ")}")
        }

        return "redirect:/crawlConfigs"
    }

    /**
     * Handle GET requests to /toggle-selected (redirect to list).
     */
    @GetMapping("/toggle-selected")
    fun toggleSelectedGet(redirectAttributes: RedirectAttributes): String {
        redirectAttributes.addFlashAttribute("error", "Please use the form to toggle selected configurations")
        return "redirect:/crawlConfigs"
    }

    /**
     * Toggle enabled status for multiple crawl configurations.
     */
    @PostMapping("/toggle-selected")
    fun toggleSelectedCrawls(
        @RequestParam(name = "selectedIds") selectedIds: List<Long>,
        @RequestParam(name = "enable") enable: Boolean,
        redirectAttributes: RedirectAttributes
    ): String {
        if (selectedIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "No configurations selected")
            return "redirect:/crawlConfigs"
        }

        val updated = mutableListOf<String>()
        for (id in selectedIds) {
            try {
                val crawlConfig = crawlConfigService.get(id)
                if (crawlConfig.enabled != enable) {
                    crawlConfigService.toggleEnabled(id)
                    updated.add(crawlConfig.displayLabel ?: crawlConfig.name ?: "Config #$id")
                }
            } catch (e: Exception) {
                // Skip configs that don't exist
            }
        }

        if (updated.isNotEmpty()) {
            val action = if (enable) "Enabled" else "Disabled"
            redirectAttributes.addFlashAttribute("message",
                "$action: ${updated.joinToString(", ")}")
        }

        return "redirect:/crawlConfigs"
    }

    /**
     * Convert a JSON array string to newline-separated text for textarea display.
     */
    private fun jsonToText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            val patterns: List<String> = objectMapper.readValue(json, object : TypeReference<List<String>>() {})
            patterns.joinToString("\n")
        } catch (e: Exception) {
            // If JSON parsing fails, return as-is (might be raw text)
            json
        }
    }

    /**
     * Convert newline-separated text from textarea to JSON array string.
     */
    private fun textToJson(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val patterns = text
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (patterns.isEmpty()) return null
        return objectMapper.writeValueAsString(patterns)
    }

}
