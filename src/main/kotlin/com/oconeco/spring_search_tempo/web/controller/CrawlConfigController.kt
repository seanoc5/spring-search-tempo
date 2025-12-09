package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.batch.fscrawl.FsCrawlJobBuilder
import com.oconeco.spring_search_tempo.batch.fscrawl.JobRunTrackingListener
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.SortDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
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
        model.addAttribute("crawlConfigs", crawlConfigs)
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
        model.addAttribute("crawlConfig", crawlConfig)
        model.addAttribute("jobRuns", jobRuns)
        model.addAttribute("page", jobRuns)
        return "crawlConfig/view"
    }

    @PostMapping("/{id}/run")
    fun runCrawl(
        @PathVariable(name = "id") id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val crawlConfig = crawlConfigService.get(id)

        try {
            // Convert database config to CrawlDefinition
            val crawlDefinition = configConverter.toDefinition(crawlConfig)

            // Build the job dynamically from the crawl config
            val job = jobBuilder.buildJob(crawlDefinition)

            // Create job parameters with crawl config ID and timestamp for uniqueness
            val jobParams = JobParametersBuilder()
                .addString(JobRunTrackingListener.CRAWL_CONFIG_ID_KEY, id.toString())
                .addLong("timestamp", Instant.now().toEpochMilli())
                .toJobParameters()

            // Launch the job asynchronously
            jobLauncher.run(job, jobParams)

            redirectAttributes.addFlashAttribute("message",
                "Crawl job started for configuration: ${crawlConfig.displayLabel ?: crawlConfig.name}")
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

}
