package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.BrowserProfileService
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.model.BrowserProfileDTO
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.batch.assignment.AnalysisAssignmentJobBuilder
import com.oconeco.spring_search_tempo.batch.bookmarkcrawl.BookmarkImportJobBuilder
import com.oconeco.spring_search_tempo.batch.discovery.DiscoveryJobBuilder
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.embedding.EmbeddingJobLauncher
import com.oconeco.spring_search_tempo.batch.fscrawl.CrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.nlp.NLPJobLauncher
import com.oconeco.spring_search_tempo.batch.onedrivesync.OneDriveSyncOrchestrator
import com.oconeco.spring_search_tempo.batch.progressive.ProgressiveAnalysisJobBuilder
import com.oconeco.spring_search_tempo.web.service.BatchAdminService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Controller for Spring Batch administration UI.
 * Provides views for job execution history, details, and control operations.
 */
@Controller
@RequestMapping("/batch")
class BatchAdminController(
    private val batchAdminService: BatchAdminService,
    private val nlpJobLauncher: NLPJobLauncher,
    private val embeddingJobLauncher: EmbeddingJobLauncher,
    private val crawlOrchestrator: CrawlOrchestrator,
    private val emailCrawlOrchestrator: EmailCrawlOrchestrator,
    private val oneDriveSyncOrchestrator: OneDriveSyncOrchestrator,
    private val jobLauncher: JobLauncher,
    private val crawlConfigService: CrawlConfigService,
    private val discoveryJobBuilder: DiscoveryJobBuilder,
    private val analysisAssignmentJobBuilder: AnalysisAssignmentJobBuilder,
    private val progressiveAnalysisJobBuilder: ProgressiveAnalysisJobBuilder,
    private val bookmarkImportJobBuilder: BookmarkImportJobBuilder,
    private val browserProfileService: BrowserProfileService,
    @Value("\${app.monitoring.grafana-batch-dashboard-url:http://localhost:3000/d/tempo-batch-overview}")
    private val grafanaBatchDashboardUrl: String
) {
    companion object {
        private val log = LoggerFactory.getLogger(BatchAdminController::class.java)

        /**
         * Set toast notification headers for HTMX responses.
         */
        fun setToastHeaders(response: HttpServletResponse, message: String, type: String = "info") {
            response.setHeader("X-Toast-Message", URLEncoder.encode(message, StandardCharsets.UTF_8))
            response.setHeader("X-Toast-Type", type)
        }
    }

    /**
     * Main batch admin dashboard showing all job executions.
     */
    @GetMapping
    fun list(
        @RequestParam(name = "tab", required = false, defaultValue = "all") tab: String,
        @RequestParam(name = "status", required = false) status: String?,
        @RequestParam(name = "jobName", required = false) jobName: String?,
        @RequestParam(name = "filter", required = false) filter: String?,
        @PageableDefault(size = 20) pageable: Pageable,
        model: Model,
        request: HttpServletRequest
    ): String {
        val isHtmx = request.getHeader("HX-Request") == "true"
        val isBoosted = request.getHeader("HX-Boosted") == "true"
        val isFullPageLoad = !isHtmx || isBoosted

        // Reality-check stale "running" records on full page loads only.
        val realityCheckReconciled = if (isFullPageLoad) {
            batchAdminService.realityCheckRunningJobsIfDue()
        } else {
            0
        }

        // Determine which filter to apply (priority: status > jobName > tab)
        val executions = when {
            !status.isNullOrBlank() -> batchAdminService.getJobExecutionsByStatus(status, pageable)
            tab == "failed" -> batchAdminService.getFailedJobExecutions(pageable)
            !jobName.isNullOrBlank() -> batchAdminService.getJobExecutionsByJobName(jobName, pageable)
            else -> batchAdminService.getAllJobExecutions(pageable)
        }

        val runningJobs = batchAdminService.getRunningJobExecutions()
        val ops = batchAdminService.getOpsSnapshot()
        val baseSummary = batchAdminService.getJobSummary()
        val staleCount = batchAdminService.getStaleJobCount()
        val staleByHeartbeat = batchAdminService.getStaleJobRunIds().size
        val summary = baseSummary.copy(staleCount = staleCount, staleByHeartbeatCount = staleByHeartbeat)
        val jobNames = batchAdminService.getJobNames()
        val configuredJobs = batchAdminService.getConfiguredJobs()
        val availableJobTypes = batchAdminService.getAvailableJobTypes()

        model.addAttribute("executions", executions)
        model.addAttribute("page", executions)
        model.addAttribute("runningJobs", runningJobs)
        model.addAttribute("ops", ops)
        model.addAttribute("summary", summary)
        model.addAttribute("jobNames", jobNames)
        model.addAttribute("configuredJobs", configuredJobs)
        model.addAttribute("availableJobTypes", availableJobTypes)
        model.addAttribute("runningJobTypeCount", availableJobTypes.count { it.isRunning })
        model.addAttribute("currentTab", tab)
        model.addAttribute("currentStatus", status)
        model.addAttribute("selectedJobName", jobName)
        model.addAttribute("filter", filter)
        model.addAttribute("staleCount", staleCount)
        model.addAttribute("grafanaBatchDashboardUrl", grafanaBatchDashboardUrl)
        model.addAttribute("realityCheckReconciled", realityCheckReconciled)

        // For HTMX partial updates
        return if (isHtmx && !isBoosted) {
            "batch/list :: table-content"
        } else {
            "batch/list"
        }
    }

    /**
     * HTMX fragment for job types status (auto-refresh).
     */
    @GetMapping("/job-types")
    fun jobTypes(model: Model): String {
        val availableJobTypes = batchAdminService.getAvailableJobTypes()
        val runningJobTypeCount = availableJobTypes.count { it.isRunning }
        model.addAttribute("availableJobTypes", availableJobTypes)
        model.addAttribute("runningJobTypeCount", runningJobTypeCount)
        return "batch/fragments/jobTypes :: job-types-content"
    }

    /**
     * HTMX fragment for running jobs status (auto-refresh).
     */
    @GetMapping("/running")
    fun runningJobs(model: Model): String {
        val runningJobs = batchAdminService.getRunningJobExecutions()
        model.addAttribute("runningJobs", runningJobs)
        return "batch/fragments/runningJobs :: running-jobs"
    }

    /**
     * HTMX fragment for operational summary cards.
     */
    @GetMapping("/ops")
    fun opsSummary(model: Model): String {
        model.addAttribute("ops", batchAdminService.getOpsSnapshot())
        model.addAttribute("grafanaBatchDashboardUrl", grafanaBatchDashboardUrl)
        return "batch/fragments/opsSummary :: ops-summary"
    }

    /**
     * Stop all currently running jobs.
     * Supports HTMX requests with toast notifications.
     */
    @PostMapping("/running/stop-all")
    fun stopAllRunning(
        request: HttpServletRequest,
        response: HttpServletResponse,
        redirectAttributes: RedirectAttributes,
        model: Model
    ): String {
        val stopped = batchAdminService.stopAllRunningJobs()
        val isHtmx = request.getHeader("HX-Request") == "true"

        return if (isHtmx) {
            val type = if (stopped > 0) "success" else "info"
            val message = if (stopped > 0) {
                "Stop requested for $stopped running job(s)"
            } else {
                "No running jobs found to stop"
            }
            setToastHeaders(response, message, type)
            model.addAttribute("runningJobs", batchAdminService.getRunningJobExecutions())
            "batch/fragments/runningJobs :: running-jobs"
        } else {
            if (stopped > 0) {
                redirectAttributes.addFlashAttribute("message", "Stop requested for $stopped running job(s)")
            } else {
                redirectAttributes.addFlashAttribute("MSG_INFO", "No running jobs found to stop")
            }
            "redirect:/batch"
        }
    }

    /**
     * Mark all currently running jobs as FAILED.
     * Supports HTMX requests with toast notifications.
     */
    @PostMapping("/running/mark-all-failed")
    fun markAllRunningFailed(
        request: HttpServletRequest,
        response: HttpServletResponse,
        redirectAttributes: RedirectAttributes,
        model: Model
    ): String {
        val marked = batchAdminService.markAllRunningJobsAsFailed("Bulk mark-failed from Batch Admin")
        val isHtmx = request.getHeader("HX-Request") == "true"

        return if (isHtmx) {
            val type = if (marked > 0) "warning" else "info"
            val message = if (marked > 0) {
                "Marked $marked running job(s) as FAILED"
            } else {
                "No running jobs found to mark as FAILED"
            }
            setToastHeaders(response, message, type)
            model.addAttribute("runningJobs", batchAdminService.getRunningJobExecutions())
            "batch/fragments/runningJobs :: running-jobs"
        } else {
            if (marked > 0) {
                redirectAttributes.addFlashAttribute("message", "Marked $marked running job(s) as FAILED")
            } else {
                redirectAttributes.addFlashAttribute("MSG_INFO", "No running jobs found to mark as FAILED")
            }
            "redirect:/batch"
        }
    }

    /**
     * View details of a specific job execution.
     */
    @GetMapping("/{executionId}")
    fun view(
        @PathVariable executionId: Long,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        val detail = batchAdminService.getJobExecution(executionId)
        if (detail == null) {
            redirectAttributes.addFlashAttribute("error", "Job execution $executionId not found")
            return "redirect:/batch"
        }

        model.addAttribute("detail", detail)
        model.addAttribute("execution", detail.execution)
        model.addAttribute("steps", detail.steps)
        model.addAttribute("jobRun", detail.jobRun)
        model.addAttribute("failureExceptions", detail.failureExceptions)

        return "batch/view"
    }

    /**
     * Stop a running job execution.
     * Supports HTMX requests with toast notifications.
     * Returns appropriate fragment based on HX-Target header.
     */
    @PostMapping("/{executionId}/stop")
    fun stop(
        @PathVariable executionId: Long,
        request: HttpServletRequest,
        response: HttpServletResponse,
        redirectAttributes: RedirectAttributes,
        model: Model
    ): String {
        val success = batchAdminService.stopJob(executionId)
        val isHtmx = request.getHeader("HX-Request") == "true"
        val htmxTarget = request.getHeader("HX-Target")

        return if (isHtmx) {
            if (success) {
                setToastHeaders(response, "Stop request sent for job execution #$executionId", "success")
            } else {
                setToastHeaders(response, "Could not stop job execution #$executionId - job may not be running", "error")
            }

            // Return appropriate fragment based on target
            when (htmxTarget) {
                "jobTypesContainer" -> {
                    val availableJobTypes = batchAdminService.getAvailableJobTypes()
                    model.addAttribute("availableJobTypes", availableJobTypes)
                    model.addAttribute("runningJobTypeCount", availableJobTypes.count { it.isRunning })
                    "batch/fragments/jobTypes :: job-types-content"
                }
                else -> {
                    val runningJobs = batchAdminService.getRunningJobExecutions()
                    model.addAttribute("runningJobs", runningJobs)
                    "batch/fragments/runningJobs :: running-jobs"
                }
            }
        } else {
            if (success) {
                redirectAttributes.addFlashAttribute("message", "Stop request sent for job execution $executionId")
            } else {
                redirectAttributes.addFlashAttribute("error", "Could not stop job execution $executionId - job may not be running")
            }
            "redirect:/batch/$executionId"
        }
    }

    /**
     * Restart a failed or stopped job execution.
     * Note: For crawl jobs, users should re-run via Crawl Configurations.
     */
    @PostMapping("/{executionId}/restart")
    fun restart(
        @PathVariable executionId: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val newExecutionId = batchAdminService.restartJob(executionId)
        if (newExecutionId != null) {
            redirectAttributes.addFlashAttribute("message", "Job restarted as execution $newExecutionId")
            return "redirect:/batch/$newExecutionId"
        } else {
            redirectAttributes.addFlashAttribute("error",
                "Could not restart job execution $executionId. For crawl jobs, please re-run via Crawl Configurations.")
            return "redirect:/batch/$executionId"
        }
    }

    /**
     * Abandon a failed job execution so it can be restarted fresh.
     */
    @PostMapping("/{executionId}/abandon")
    fun abandon(
        @PathVariable executionId: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val success = batchAdminService.abandonJob(executionId)
        if (success) {
            redirectAttributes.addFlashAttribute("message", "Job execution $executionId abandoned")
        } else {
            redirectAttributes.addFlashAttribute("error", "Could not abandon job execution $executionId - only FAILED jobs can be abandoned")
        }
        return "redirect:/batch/$executionId"
    }

    /**
     * Mark a single job execution as FAILED.
     * Used for cleaning up orphaned "running" jobs.
     * Supports HTMX requests with toast notifications.
     */
    @PostMapping("/{executionId}/mark-failed")
    fun markFailed(
        @PathVariable executionId: Long,
        request: HttpServletRequest,
        response: HttpServletResponse,
        redirectAttributes: RedirectAttributes,
        model: Model
    ): String {
        val success = batchAdminService.markJobAsFailed(executionId, "Manually marked as failed by admin")
        val isHtmx = request.getHeader("HX-Request") == "true"

        return if (isHtmx) {
            // Return toast notification via headers and refresh running jobs fragment
            if (success) {
                setToastHeaders(response, "Job execution #$executionId marked as FAILED", "success")
            } else {
                setToastHeaders(response, "Could not mark job execution #$executionId as failed", "error")
            }
            // Return updated running jobs fragment
            val runningJobs = batchAdminService.getRunningJobExecutions()
            model.addAttribute("runningJobs", runningJobs)
            "batch/fragments/runningJobs :: running-jobs"
        } else {
            if (success) {
                redirectAttributes.addFlashAttribute("message", "Job execution $executionId marked as FAILED")
            } else {
                redirectAttributes.addFlashAttribute("error", "Could not mark job execution $executionId as failed")
            }
            "redirect:/batch/$executionId"
        }
    }

    /**
     * Clean up all stale (orphaned) job executions.
     * Jobs running longer than the threshold are marked as FAILED.
     * Supports HTMX requests with toast notifications.
     */
    @PostMapping("/cleanup-stale")
    fun cleanupStale(
        @RequestParam(name = "thresholdHours", required = false, defaultValue = "4") thresholdHours: Long,
        request: HttpServletRequest,
        response: HttpServletResponse,
        redirectAttributes: RedirectAttributes,
        model: Model
    ): String {
        val cleanedUp = batchAdminService.cleanupStaleJobs(thresholdHours)
        val isHtmx = request.getHeader("HX-Request") == "true"

        return if (isHtmx) {
            if (cleanedUp > 0) {
                setToastHeaders(response, "Cleaned up $cleanedUp stale job execution(s)", "success")
            } else {
                setToastHeaders(response, "No stale jobs found to clean up", "info")
            }
            // Return updated running jobs fragment
            val runningJobs = batchAdminService.getRunningJobExecutions()
            model.addAttribute("runningJobs", runningJobs)
            "batch/fragments/runningJobs :: running-jobs"
        } else {
            if (cleanedUp > 0) {
                redirectAttributes.addFlashAttribute("message", "Cleaned up $cleanedUp stale job execution(s)")
            } else {
                redirectAttributes.addFlashAttribute("MSG_INFO", "No stale jobs found to clean up")
            }
            "redirect:/batch"
        }
    }

    /**
     * Run a batch job by name.
     * Dispatches to the appropriate job launcher based on job type.
     */
    @PostMapping("/run")
    fun runJob(
        @RequestParam(name = "jobName") jobName: String,
        redirectAttributes: RedirectAttributes
    ): String {
        log.info("Request to run job: {}", jobName)

        return try {
            when {
                // NLP Processing Job
                jobName == "nlpProcessingJob" || jobName.startsWith("nlpProcessing") -> {
                    val execution = nlpJobLauncher.launchNLPJob("batch-admin")
                    return redirectToExecution(jobName, execution, redirectAttributes)
                }

                // Embedding Processing Job
                jobName == "embeddingProcessingJob" || jobName.startsWith("embeddingProcessing") -> {
                    val execution = embeddingJobLauncher.launchEmbeddingJob("batch-admin")
                    return redirectToExecution(jobName, execution, redirectAttributes)
                }

                // File System Crawl Job - extract config name
                jobName.startsWith("fsCrawlJob") -> {
                    // Job name format: fsCrawlJob_configName or just fsCrawlJob
                    val configName = if (jobName.contains("_")) {
                        jobName.substringAfter("fsCrawlJob_")
                    } else {
                        // Run all enabled crawls
                        val results = crawlOrchestrator.executeAllCrawls()
                        val successful = results.values.count { !it.status.isUnsuccessful }
                        redirectAttributes.addFlashAttribute("message",
                            "Started ${results.size} crawl job(s), $successful successful")
                        return "redirect:/batch"
                    }

                    val results = crawlOrchestrator.executeCrawlsByName(configName)
                    if (results.isEmpty()) {
                        redirectAttributes.addFlashAttribute("error",
                            "Crawl config '$configName' not found. Use Crawl Configurations to run crawls.")
                        return "redirect:/batch"
                    }
                    return redirectToExecution(jobName, results.values.first(), redirectAttributes)
                }

                // Email Quick Sync Job - extract account ID
                jobName.startsWith("emailQuickSync") -> {
                    val accountId = jobName.substringAfter("emailQuickSync_").toLongOrNull()
                    if (accountId != null) {
                        try {
                            val execution = emailCrawlOrchestrator.runQuickSyncForAccount(accountId)
                            redirectAttributes.addFlashAttribute("message",
                                "Email sync started for account $accountId (execution #${execution.id})")
                            return "redirect:/batch/${execution.id}"
                        } catch (e: com.oconeco.spring_search_tempo.base.util.NotFoundException) {
                            redirectAttributes.addFlashAttribute("error",
                                "Email account $accountId not found. It may have been deleted.")
                            return "redirect:/batch"
                        }
                    } else {
                        // Run all enabled accounts
                        val results = emailCrawlOrchestrator.runQuickSync()
                        redirectAttributes.addFlashAttribute("message",
                            "Email sync started for ${results.size} account(s)")
                        return "redirect:/batch"
                    }
                }

                // OneDrive sync for all enabled accounts
                jobName == "oneDriveSyncJob" || jobName == "oneDriveSync" -> {
                    val results = oneDriveSyncOrchestrator.runSyncExecutions()
                    if (results.isEmpty()) {
                        redirectAttributes.addFlashAttribute("error",
                            "No enabled OneDrive accounts found or OneDrive integration is disabled.")
                        return "redirect:/batch"
                    }
                    redirectAttributes.addFlashAttribute("message",
                        "Started OneDrive sync for ${results.size} account(s)")
                    return "redirect:/batch"
                }

                // OneDrive sync for one account (job names from history)
                jobName.startsWith("oneDriveSync_") || jobName.startsWith("oneDriveSyncJob_") -> {
                    val raw = if (jobName.startsWith("oneDriveSync_")) {
                        jobName.removePrefix("oneDriveSync_")
                    } else {
                        jobName.removePrefix("oneDriveSyncJob_")
                    }
                    val accountId = raw.substringBefore("_").toLongOrNull()
                    if (accountId == null) {
                        redirectAttributes.addFlashAttribute("error",
                            "Could not determine OneDrive account from job name: $jobName")
                        return "redirect:/batch"
                    }
                    val execution = oneDriveSyncOrchestrator.runSyncExecutionForAccount(accountId)
                    return redirectToExecution(jobName, execution, redirectAttributes)
                }

                // Bookmark import for all enabled profiles
                jobName == "bookmarkImportJob" -> {
                    val enabledProfiles = browserProfileService.findEnabled()
                    if (enabledProfiles.isEmpty()) {
                        redirectAttributes.addFlashAttribute("error",
                            "No enabled browser profiles found for bookmark import.")
                        return "redirect:/batch"
                    }
                    enabledProfiles.forEach { launchBookmarkImportForProfile(it) }
                    redirectAttributes.addFlashAttribute("message",
                        "Started bookmark import for ${enabledProfiles.size} profile(s)")
                    return "redirect:/batch"
                }

                // Bookmark import for one profile (job name from history)
                jobName.startsWith("bookmarkImportJob_") -> {
                    val profileId = jobName.substringAfter("bookmarkImportJob_").toLongOrNull()
                    if (profileId == null) {
                        redirectAttributes.addFlashAttribute("error",
                            "Could not determine browser profile from job name: $jobName")
                        return "redirect:/batch"
                    }
                    val profile = browserProfileService.get(profileId)
                    val execution = launchBookmarkImportForProfile(profile)
                    return redirectToExecution(jobName, execution, redirectAttributes)
                }

                // Discovery for all enabled crawls
                jobName == "discoveryJob" -> {
                    val crawls = crawlConfigService.getEnabledCrawls()
                    if (crawls.isEmpty()) {
                        redirectAttributes.addFlashAttribute("error",
                            "No enabled crawl configs found for discovery.")
                        return "redirect:/batch"
                    }
                    crawls.forEach { launchDiscoveryForCrawl(it) }
                    redirectAttributes.addFlashAttribute("message",
                        "Started discovery for ${crawls.size} crawl config(s)")
                    return "redirect:/batch"
                }

                // Discovery for a specific crawl (job name from history)
                jobName.startsWith("discoveryJob_") -> {
                    val crawlName = jobName.substringAfter("discoveryJob_")
                    val crawl = crawlConfigService.getCrawlByName(crawlName)
                    if (crawl == null) {
                        redirectAttributes.addFlashAttribute("error",
                            "Discovery crawl config '$crawlName' not found.")
                        return "redirect:/batch"
                    }
                    val execution = launchDiscoveryForCrawl(crawl)
                    return redirectToExecution(jobName, execution, redirectAttributes)
                }

                // Analysis assignment for all content
                jobName == "analysisAssignmentJob" || jobName == "globalAssignmentJob" -> {
                    val execution = launchGlobalAssignment()
                    return redirectToExecution(jobName, execution, redirectAttributes)
                }

                // Analysis assignment for a specific crawl
                jobName.startsWith("assignmentJob_") || jobName.startsWith("analysisAssignmentJob_") -> {
                    val crawlName = if (jobName.startsWith("assignmentJob_")) {
                        jobName.substringAfter("assignmentJob_")
                    } else {
                        jobName.substringAfter("analysisAssignmentJob_")
                    }
                    val crawl = crawlConfigService.getCrawlByName(crawlName)
                    if (crawl == null) {
                        redirectAttributes.addFlashAttribute("error",
                            "Assignment crawl config '$crawlName' not found.")
                        return "redirect:/batch"
                    }
                    val execution = launchAssignmentForCrawl(crawl)
                    return redirectToExecution(jobName, execution, redirectAttributes)
                }

                // Progressive analysis (global)
                jobName == "progressiveAnalysisJob" || jobName.startsWith("progressiveAnalysisJob") -> {
                    val execution = launchProgressiveAnalysis()
                    return redirectToExecution(jobName, execution, redirectAttributes)
                }

                else -> {
                    redirectAttributes.addFlashAttribute("error",
                        "Unknown job type: $jobName. Cannot run automatically.")
                    return "redirect:/batch"
                }
            }
        } catch (e: Exception) {
            log.error("Failed to run job {}: {}", jobName, e.message, e)
            redirectAttributes.addFlashAttribute("error",
                "Failed to run job '$jobName': ${e.message}")
            "redirect:/batch"
        }
    }

    private fun redirectToExecution(
        jobName: String,
        execution: JobExecution,
        redirectAttributes: RedirectAttributes
    ): String {
        redirectAttributes.addFlashAttribute(
            "message",
            "Job '$jobName' started successfully (execution #${execution.id})"
        )
        return "redirect:/batch/${execution.id}"
    }

    private fun launchDiscoveryForCrawl(crawl: CrawlDefinition): JobExecution {
        val job = discoveryJobBuilder.buildJob(crawl)
        val params = JobParametersBuilder()
            .addString("crawlName", crawl.name)
            .addString("startPaths", crawl.startPaths.joinToString(","))
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()
        return jobLauncher.run(job, params)
    }

    private fun launchAssignmentForCrawl(crawl: CrawlDefinition): JobExecution {
        val job = analysisAssignmentJobBuilder.buildJob(crawl)
        val params = JobParametersBuilder()
            .addString("crawlName", crawl.name)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()
        return jobLauncher.run(job, params)
    }

    private fun launchGlobalAssignment(): JobExecution {
        val job = analysisAssignmentJobBuilder.buildGlobalJob()
        val params = JobParametersBuilder()
            .addLong("timestamp", System.currentTimeMillis())
            .addString("triggeredBy", "batch-admin")
            .toJobParameters()
        return jobLauncher.run(job, params)
    }

    private fun launchProgressiveAnalysis(): JobExecution {
        val job = progressiveAnalysisJobBuilder.buildJob(processAll = true)
        val params = JobParametersBuilder()
            .addLong("timestamp", System.currentTimeMillis())
            .addString("triggeredBy", "batch-admin")
            .toJobParameters()
        return jobLauncher.run(job, params)
    }

    private fun launchBookmarkImportForProfile(profile: BrowserProfileDTO): JobExecution {
        val profileId = profile.id ?: throw IllegalArgumentException("Browser profile ID is required")
        val job = bookmarkImportJobBuilder.buildJob(profile)
        val params = JobParametersBuilder()
            .addString("profileId", profileId.toString())
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()
        return jobLauncher.run(job, params)
    }

    /**
     * Re-run a job based on a previous execution.
     * Uses the job name from the execution to trigger a new run.
     */
    @PostMapping("/{executionId}/rerun")
    fun rerunJob(
        @PathVariable executionId: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val detail = batchAdminService.getJobExecution(executionId)
        if (detail == null) {
            redirectAttributes.addFlashAttribute("error", "Job execution $executionId not found")
            return "redirect:/batch"
        }

        val jobName = detail.execution.jobName
        log.info("Re-running job {} based on execution {}", jobName, executionId)

        // Delegate to the run endpoint
        return runJob(jobName, redirectAttributes)
    }
}
