package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
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
    private val configConverter: CrawlConfigConverter
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

        model.addAttribute("crawlConfigs", crawlConfigs)
        model.addAttribute("fileCounts", fileCountsMap)
        model.addAttribute("filter", filter)
        model.addAttribute("page", crawlConfigs)
        return "crawlConfig/list"
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
        return "crawlConfig/edit"
    }

    @PostMapping("/{id}/edit")
    fun edit(
        @PathVariable(name = "id") id: Long,
        @ModelAttribute("crawlConfig") @Valid crawlConfigDTO: CrawlConfigDTO,
        @RequestParam(name = "startPathsText", required = false) startPathsText: String?,
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

        if (bindingResult.hasErrors()) {
            model.addAttribute("startPathsText", startPathsText)
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
        redirectAttributes: RedirectAttributes
    ): String {
        val crawlConfig = crawlConfigService.get(id)

        try {
            // Convert database config to CrawlDefinition
            val crawlDefinition = configConverter.toDefinition(crawlConfig)

            // Build the job dynamically from the crawl config
            val job = jobBuilder.buildJob(crawlDefinition, forceFullRecrawl)

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
        @SortDefault(sort = ["uri"]) @PageableDefault(size = 50) pageable: Pageable,
        model: Model
    ): String {
        val crawlConfig = crawlConfigService.get(id)
        val files = fileService.findByCrawlConfigId(id, pageable, showSkipped)

        model.addAttribute("crawlConfig", crawlConfig)
        model.addAttribute("files", files)
        model.addAttribute("page", files)
        model.addAttribute("showSkipped", showSkipped)
        return "crawlConfig/files"
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

    @GetMapping("/{id}/folders")
    fun browseFolders(
        @PathVariable(name = "id") id: Long,
        @RequestParam(name = "showSkipped", required = false, defaultValue = "false") showSkipped: Boolean,
        @SortDefault(sort = ["uri"]) @PageableDefault(size = 50) pageable: Pageable,
        model: Model
    ): String {
        val crawlConfig = crawlConfigService.get(id)
        val folders = folderService.findByCrawlConfigId(id, pageable, showSkipped)

        model.addAttribute("crawlConfig", crawlConfig)
        model.addAttribute("folders", folders)
        model.addAttribute("page", folders)
        model.addAttribute("showSkipped", showSkipped)
        return "crawlConfig/folders"
    }

}
