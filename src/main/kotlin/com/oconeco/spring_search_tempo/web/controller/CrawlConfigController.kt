package com.oconeco.spring_search_tempo.web.controller

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.UserOwnershipService
import com.oconeco.spring_search_tempo.base.config.HostNameHolder
import com.oconeco.spring_search_tempo.base.domain.DiscoveryManualOverride
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.base.service.CrawlDataCleanupService
import com.oconeco.spring_search_tempo.base.service.SmartDeleteService
import com.oconeco.spring_search_tempo.base.util.WebUtils
import com.oconeco.spring_search_tempo.web.model.BaselineSamplingPolicy
import com.oconeco.spring_search_tempo.web.model.ValidationFilterDTO
import com.oconeco.spring_search_tempo.web.service.CrawlDiscoveryObservationService
import com.oconeco.spring_search_tempo.web.service.CrawlConfigValidationService
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
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Instant

@Controller
@RequestMapping("/crawlConfigs")
class CrawlConfigController(
    private val crawlConfigService: DatabaseCrawlConfigService,
    private val jobRunService: JobRunService,
    private val fileService: FSFileService,
    private val folderService: FSFolderService,
    private val chunkService: ContentChunkService,
    private val jobLauncher: JobLauncher,
    private val jobBuilder: FsCrawlJobBuilder,
    private val configConverter: CrawlConfigConverter,
    private val cleanupService: CrawlDataCleanupService,
    private val smartDeleteService: SmartDeleteService,
    private val objectMapper: ObjectMapper,
    private val crawlConfigValidationService: CrawlConfigValidationService,
    private val crawlDiscoveryObservationService: CrawlDiscoveryObservationService,
    private val userOwnershipService: UserOwnershipService
) {

    @ModelAttribute("currentHostName")
    fun currentHostName(): String = HostNameHolder.currentHostName

    @ModelAttribute("knownHosts")
    fun knownHosts(): List<String> = crawlConfigService.findDistinctSourceHosts()

    @GetMapping
    fun list(
        @RequestParam(name = "filter", required = false) filter: String?,
        @RequestParam(name = "sourceHost", required = false) sourceHost: String?,
        @RequestParam(name = "showAll", required = false, defaultValue = "false") showAll: Boolean,
        @SortDefault(sort = ["name"]) @PageableDefault(size = 50) pageable: Pageable,
        model: Model,
        request: HttpServletRequest
    ): String {
        val sourceHosts = crawlConfigService.findDistinctSourceHosts()
        val isAdmin = userOwnershipService.isCurrentUserAdmin()

        // Determine active tab: first tab or requested sourceHost
        val activeHost = sourceHost ?: sourceHosts.firstOrNull()

        model.addAttribute("sourceHosts", sourceHosts)
        model.addAttribute("activeHost", activeHost)
        model.addAttribute("showAll", showAll)
        model.addAttribute("isAdmin", isAdmin)
        model.addAttribute("filter", filter)

        // If this is an HTMX request for the table fragment, return just the table
        val isHtmxRequest = request.getHeader("HX-Request") == "true"
        val htmxTarget = request.getHeader("HX-Target")

        if (activeHost != null) {
            populateConfigTableModel(model, activeHost, filter, pageable, showAll, isAdmin)
        }

        // Return fragment for HTMX table/tab requests
        if (isHtmxRequest && (htmxTarget == "configTableContainer" || htmxTarget?.startsWith("tab-") == true)) {
            return "crawlConfig/fragments/configTable :: configTable"
        }

        return "crawlConfig/list"
    }

    @GetMapping("/tab/{sourceHost}")
    fun tabContent(
        @PathVariable sourceHost: String,
        @RequestParam(name = "filter", required = false) filter: String?,
        @RequestParam(name = "showAll", required = false, defaultValue = "false") showAll: Boolean,
        @SortDefault(sort = ["name"]) @PageableDefault(size = 50) pageable: Pageable,
        model: Model
    ): String {
        val isAdmin = userOwnershipService.isCurrentUserAdmin()
        model.addAttribute("activeHost", sourceHost)
        model.addAttribute("filter", filter)
        model.addAttribute("showAll", showAll)
        model.addAttribute("isAdmin", isAdmin)

        populateConfigTableModel(model, sourceHost, filter, pageable, showAll, isAdmin)

        return "crawlConfig/fragments/configTable :: configTable"
    }

    private fun populateConfigTableModel(
        model: Model,
        sourceHost: String,
        filter: String?,
        pageable: Pageable,
        showAll: Boolean,
        isAdmin: Boolean
    ) {
        val crawlConfigs = crawlConfigService.findBySourceHost(sourceHost, filter, pageable, showAll && isAdmin)

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

        // Processing pipeline stats (bulk queries)
        val searchableCountsMap = fileService.countSearchableByCrawlConfig()
        val nlpProcessedCountsMap = chunkService.countFilesWithNlpByCrawlConfig()
        val embeddingCountsMap = chunkService.countFilesWithEmbeddingByCrawlConfig()

        model.addAttribute("crawlConfigs", crawlConfigs)
        model.addAttribute("fileCounts", fileCountsMap)
        model.addAttribute("folderCounts", folderCountsMap)
        model.addAttribute("lastRuns", lastRunMap)
        model.addAttribute("searchableCounts", searchableCountsMap)
        model.addAttribute("nlpProcessedCounts", nlpProcessedCountsMap)
        model.addAttribute("embeddingCounts", embeddingCountsMap)
        model.addAttribute("page", crawlConfigs)
    }

    @GetMapping("/add")
    fun add(model: Model): String {
        model.addAttribute("crawlConfig", CrawlConfigDTO().apply {
            maxDepth = 50
            followLinks = false
            parallel = false
            enabled = true
            sourceHost = HostNameHolder.currentHostName
        })
        model.addAttribute("startPathsText", "")
        model.addAttribute("folderPatternsSkipText", "")
        model.addAttribute("folderPatternsLocateText", "")
        model.addAttribute("folderPatternsIndexText", "")
        model.addAttribute("folderPatternsAnalyzeText", "")
        model.addAttribute("folderPatternsSemanticText", "")
        model.addAttribute("filePatternsSkipText", "")
        model.addAttribute("filePatternsLocateText", "")
        model.addAttribute("filePatternsIndexText", "")
        model.addAttribute("filePatternsAnalyzeText", "")
        model.addAttribute("filePatternsSemanticText", "")
        return "crawlConfig/add"
    }

    @GetMapping("/wizard")
    fun wizard(model: Model): String {
        val presetCountsByOs = WizardOs.entries.associate { os ->
            os.name to buildPresetBundle(os).size
        }
        val presetPreviewByOs = WizardOs.entries.associate { os ->
            os.name to buildPresetBundle(os).map { "${it.name} - ${it.label}" }
        }

        model.addAttribute("osOptions", WizardOs.entries.map { WizardOsOption(it.name, it.displayName) })
        model.addAttribute("defaultOs", WizardOs.LINUX.name)
        model.addAttribute("presetCountsByOs", presetCountsByOs)
        model.addAttribute("presetPreviewByOs", presetPreviewByOs)
        model.addAttribute("defaultSourceHost", HostNameHolder.currentHostName)
        return "crawlConfig/wizard"
    }

    @PostMapping("/wizard/create")
    fun wizardCreate(
        @RequestParam(name = "os") os: String,
        @RequestParam(name = "sourceHost", required = false) sourceHost: String?,
        @RequestParam(name = "followLinks", defaultValue = "false") followLinks: Boolean,
        @RequestParam(name = "parallel", defaultValue = "true") parallel: Boolean,
        redirectAttributes: RedirectAttributes
    ): String {
        val osType = runCatching { WizardOs.valueOf(os.uppercase()) }.getOrElse {
            redirectAttributes.addFlashAttribute("error", "Invalid OS selection")
            return "redirect:/crawlConfigs/wizard"
        }

        return try {
            val presets = buildPresetBundle(osType)
            if (presets.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "No presets available for ${osType.displayName}")
                return "redirect:/crawlConfigs/wizard"
            }

            val resolvedSourceHost = sourceHost?.trim()?.ifBlank { null } ?: HostNameHolder.currentHostName
            val created = mutableListOf<Pair<Long, String>>()
            val skipped = mutableListOf<String>()

            for (preset in presets) {
                val normalizedName = preset.name.trim().uppercase()
                if (crawlConfigService.nameExists(normalizedName, sourceHost = resolvedSourceHost)) {
                    skipped.add(normalizedName)
                    continue
                }

                val dto = CrawlConfigDTO().apply {
                    this.name = normalizedName
                    this.label = preset.label
                    this.description = preset.description
                    this.startPaths = preset.startPaths
                    this.maxDepth = preset.maxDepth
                    this.followLinks = followLinks || preset.forceFollowLinks
                    this.parallel = parallel && preset.parallelRecommended
                    this.enabled = true
                    this.sourceHost = resolvedSourceHost

                    folderPatternsSkip = listToJson(preset.folderSkipPatterns)
                    folderPatternsLocate = listToJson(preset.folderLocatePatterns)
                    folderPatternsIndex = listToJson(preset.folderIndexPatterns)
                    folderPatternsAnalyze = listToJson(preset.folderAnalyzePatterns)
                    filePatternsSkip = listToJson(preset.fileSkipPatterns)
                    filePatternsLocate = listToJson(preset.fileLocatePatterns)
                    filePatternsIndex = listToJson(preset.fileIndexPatterns)
                    filePatternsAnalyze = listToJson(preset.fileAnalyzePatterns)
                }

                val id = crawlConfigService.create(dto)
                created.add(id to (dto.label ?: normalizedName))
            }

            if (created.isEmpty()) {
                val reason = if (skipped.isNotEmpty()) {
                    "All ${presets.size} preset names already exist for host '$resolvedSourceHost'. Rename existing configs or remove duplicates."
                } else {
                    "No preset configs were created."
                }
                redirectAttributes.addFlashAttribute("error", reason)
                return "redirect:/crawlConfigs/wizard"
            }

            redirectAttributes.addFlashAttribute(
                "message",
                "Created ${created.size}/${presets.size} ${osType.displayName} POWER_USER preset configs."
            )
            if (skipped.isNotEmpty()) {
                redirectAttributes.addFlashAttribute(
                    "warning",
                    "Skipped existing config names: ${skipped.joinToString(", ")}"
                )
            }

            if (created.size == 1) {
                return "redirect:/crawlConfigs/${created.first().first}/edit"
            }
            "redirect:/crawlConfigs"
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error", "Failed to create preset config: ${e.message}")
            "redirect:/crawlConfigs/wizard"
        }
    }

    @PostMapping("/add")
    fun add(
        @ModelAttribute("crawlConfig") @Valid crawlConfigDTO: CrawlConfigDTO,
        @RequestParam(name = "startPathsText", required = false) startPathsText: String?,
        @RequestParam(name = "folderPatternsSkipText", required = false) folderPatternsSkipText: String?,
        @RequestParam(name = "folderPatternsLocateText", required = false) folderPatternsLocateText: String?,
        @RequestParam(name = "folderPatternsIndexText", required = false) folderPatternsIndexText: String?,
        @RequestParam(name = "folderPatternsAnalyzeText", required = false) folderPatternsAnalyzeText: String?,
        @RequestParam(name = "folderPatternsSemanticText", required = false) folderPatternsSemanticText: String?,
        @RequestParam(name = "filePatternsSkipText", required = false) filePatternsSkipText: String?,
        @RequestParam(name = "filePatternsLocateText", required = false) filePatternsLocateText: String?,
        @RequestParam(name = "filePatternsIndexText", required = false) filePatternsIndexText: String?,
        @RequestParam(name = "filePatternsAnalyzeText", required = false) filePatternsAnalyzeText: String?,
        @RequestParam(name = "filePatternsSemanticText", required = false) filePatternsSemanticText: String?,
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
        crawlConfigDTO.folderPatternsSemantic = textToJson(folderPatternsSemanticText)
        crawlConfigDTO.filePatternsSkip = textToJson(filePatternsSkipText)
        crawlConfigDTO.filePatternsLocate = textToJson(filePatternsLocateText)
        crawlConfigDTO.filePatternsIndex = textToJson(filePatternsIndexText)
        crawlConfigDTO.filePatternsAnalyze = textToJson(filePatternsAnalyzeText)
        crawlConfigDTO.filePatternsSemantic = textToJson(filePatternsSemanticText)

        if (bindingResult.hasErrors()) {
            model.addAttribute("startPathsText", startPathsText)
            model.addAttribute("folderPatternsSkipText", folderPatternsSkipText)
            model.addAttribute("folderPatternsLocateText", folderPatternsLocateText)
            model.addAttribute("folderPatternsIndexText", folderPatternsIndexText)
            model.addAttribute("folderPatternsAnalyzeText", folderPatternsAnalyzeText)
            model.addAttribute("folderPatternsSemanticText", folderPatternsSemanticText)
            model.addAttribute("filePatternsSkipText", filePatternsSkipText)
            model.addAttribute("filePatternsLocateText", filePatternsLocateText)
            model.addAttribute("filePatternsIndexText", filePatternsIndexText)
            model.addAttribute("filePatternsAnalyzeText", filePatternsAnalyzeText)
            model.addAttribute("filePatternsSemanticText", filePatternsSemanticText)
            return "crawlConfig/add"
        }

        try {
            val newId = crawlConfigService.create(crawlConfigDTO)
            redirectAttributes.addFlashAttribute("message",
                "Configuration created: ${crawlConfigDTO.label ?: crawlConfigDTO.name}")
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

        // Processing pipeline stats for this config
        val searchableCount = fileService.countSearchableByCrawlConfig()[id] ?: 0L
        val nlpProcessedCount = chunkService.countFilesWithNlpByCrawlConfig()[id] ?: 0L
        val embeddingCount = chunkService.countFilesWithEmbeddingByCrawlConfig()[id] ?: 0L

        model.addAttribute("crawlConfig", crawlConfig)
        model.addAttribute("crawlConfigId", id)
        model.addAttribute("jobRuns", jobRuns)
        model.addAttribute("page", jobRuns)
        model.addAttribute("totalFileCount", totalFileCount)
        model.addAttribute("totalFolderCount", totalFolderCount)
        model.addAttribute("jobRunFileCounts", jobRunFileCounts)
        model.addAttribute("jobRunFolderCounts", jobRunFolderCounts)
        model.addAttribute("searchableCount", searchableCount)
        model.addAttribute("nlpProcessedCount", nlpProcessedCount)
        model.addAttribute("embeddingCount", embeddingCount)

        // Pattern text for inline editing display
        model.addAttribute("folderPatternsSkipText", jsonToText(crawlConfig.folderPatternsSkip))
        model.addAttribute("folderPatternsLocateText", jsonToText(crawlConfig.folderPatternsLocate))
        model.addAttribute("folderPatternsIndexText", jsonToText(crawlConfig.folderPatternsIndex))
        model.addAttribute("folderPatternsAnalyzeText", jsonToText(crawlConfig.folderPatternsAnalyze))
        model.addAttribute("folderPatternsSemanticText", jsonToText(crawlConfig.folderPatternsSemantic))
        model.addAttribute("filePatternsSkipText", jsonToText(crawlConfig.filePatternsSkip))
        model.addAttribute("filePatternsLocateText", jsonToText(crawlConfig.filePatternsLocate))
        model.addAttribute("filePatternsIndexText", jsonToText(crawlConfig.filePatternsIndex))
        model.addAttribute("filePatternsAnalyzeText", jsonToText(crawlConfig.filePatternsAnalyze))
        model.addAttribute("filePatternsSemanticText", jsonToText(crawlConfig.filePatternsSemantic))
        model.addAttribute("validationSummaries", crawlConfigValidationService.getFolderSummaries(id))

        return "crawlConfig/view"
    }

    @GetMapping("/{id}/discovery-review")
    fun discoveryReview(
        @PathVariable(name = "id") id: Long,
        @RequestParam(name = "host", required = false) host: String?,
        @RequestParam(name = "pathPrefix", required = false) pathPrefix: String?,
        @RequestParam(name = "includeSamples", required = false, defaultValue = "true") includeSamples: Boolean,
        @RequestParam(name = "page", required = false, defaultValue = "0") page: Int,
        @RequestParam(name = "limit", required = false, defaultValue = "500") limit: Int,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        val crawlConfig = crawlConfigService.get(id)
        val effectiveHost = host?.trim()?.ifBlank { null }
            ?: crawlConfig.sourceHost?.trim()?.ifBlank { null }
            ?: HostNameHolder.currentHostName

        return try {
            ensureHostAccess(effectiveHost)
            val observations = crawlDiscoveryObservationService.listObservations(
                crawlConfigId = id,
                host = effectiveHost,
                pathPrefix = pathPrefix,
                includeSamples = includeSamples,
                page = page,
                limit = limit
            )

            model.addAttribute("crawlConfig", crawlConfig)
            model.addAttribute("observations", observations)
            model.addAttribute("host", effectiveHost)
            model.addAttribute("pathPrefix", pathPrefix ?: "")
            model.addAttribute("includeSamples", includeSamples)
            model.addAttribute("page", page.coerceAtLeast(0))
            model.addAttribute("limit", limit.coerceIn(1, 5000))
            model.addAttribute("overrideValues", DiscoveryManualOverride.entries)
            "crawlConfig/discoveryReview"
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error", "Failed to load discovery review: ${e.message}")
            "redirect:/crawlConfigs/$id"
        }
    }

    @PostMapping("/{id}/discovery-review/reapply")
    fun reapplyDiscoverySkipRules(
        @PathVariable(name = "id") id: Long,
        @RequestParam(name = "host") host: String,
        @RequestParam(name = "pathPrefix", required = false) pathPrefix: String?,
        @RequestParam(name = "includeSamples", required = false, defaultValue = "true") includeSamples: Boolean,
        @RequestParam(name = "page", required = false, defaultValue = "0") page: Int,
        @RequestParam(name = "limit", required = false, defaultValue = "500") limit: Int,
        redirectAttributes: RedirectAttributes
    ): String {
        return try {
            ensureHostAccess(host)
            val result = crawlDiscoveryObservationService.reapplySkipRules(
                crawlConfigId = id,
                host = host
            )
            val suggestEnforce = crawlDiscoveryObservationService.shouldSuggestEnforce(
                crawlConfigId = id,
                host = host
            )
            redirectAttributes.addFlashAttribute(
                "message",
                "Reapplied skip rules: ${result.changed}/${result.total} observations changed" +
                    if (suggestEnforce) " (suggest ENFORCE)" else ""
            )
            "redirect:/crawlConfigs/$id/discovery-review?host=${urlEncode(host)}&pathPrefix=${urlEncode(pathPrefix)}" +
                "&includeSamples=$includeSamples&limit=$limit&page=${page.coerceAtLeast(0)}"
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error", "Reapply failed: ${e.message}")
            "redirect:/crawlConfigs/$id/discovery-review?host=${urlEncode(host)}&pathPrefix=${urlEncode(pathPrefix)}" +
                "&includeSamples=$includeSamples&limit=$limit&page=${page.coerceAtLeast(0)}"
        }
    }

    @PostMapping("/{id}/discovery-review/override")
    fun updateDiscoveryManualOverride(
        @PathVariable(name = "id") id: Long,
        @RequestParam(name = "host") host: String,
        @RequestParam(name = "path") path: String,
        @RequestParam(name = "manualOverride", required = false) manualOverride: String?,
        @RequestParam(name = "pathPrefix", required = false) pathPrefix: String?,
        @RequestParam(name = "includeSamples", required = false, defaultValue = "true") includeSamples: Boolean,
        @RequestParam(name = "page", required = false, defaultValue = "0") page: Int,
        @RequestParam(name = "limit", required = false, defaultValue = "500") limit: Int,
        redirectAttributes: RedirectAttributes
    ): String {
        return try {
            ensureHostAccess(host)
            val parsedOverride = manualOverride
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { DiscoveryManualOverride.valueOf(it.uppercase()) }

            val updated = crawlDiscoveryObservationService.updateManualOverride(
                crawlConfigId = id,
                host = host,
                path = path,
                manualOverride = parsedOverride
            )
            redirectAttributes.addFlashAttribute(
                "message",
                "Updated override for ${updated.path}: ${updated.manualOverride ?: "DEFAULT"}"
            )
            "redirect:/crawlConfigs/$id/discovery-review?host=${urlEncode(host)}&pathPrefix=${urlEncode(pathPrefix)}" +
                "&includeSamples=$includeSamples&limit=$limit&page=${page.coerceAtLeast(0)}"
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error", "Override update failed: ${e.message}")
            "redirect:/crawlConfigs/$id/discovery-review?host=${urlEncode(host)}&pathPrefix=${urlEncode(pathPrefix)}" +
                "&includeSamples=$includeSamples&limit=$limit&page=${page.coerceAtLeast(0)}"
        }
    }

    @GetMapping("/{id}/validate/folders")
    fun validationFolders(
        @PathVariable(name = "id") id: Long,
        model: Model
    ): String {
        model.addAttribute("crawlConfigId", id)
        model.addAttribute("validationSummaries", crawlConfigValidationService.getFolderSummaries(id))
        return "crawlConfig/fragments/validation :: folderSummaryTable"
    }

    @GetMapping("/{id}/validate/folders/{folderId}")
    fun validationFolderDiff(
        @PathVariable(name = "id") id: Long,
        @PathVariable(name = "folderId") folderId: Long,
        @RequestParam(name = "onlyMismatches", required = false, defaultValue = "true") onlyMismatches: Boolean,
        @RequestParam(name = "onlyStatusDrift", required = false, defaultValue = "false") onlyStatusDrift: Boolean,
        @RequestParam(name = "onlyMissingOrNew", required = false, defaultValue = "false") onlyMissingOrNew: Boolean,
        @RequestParam(name = "status", required = false) status: String?,
        model: Model
    ): String {
        val statusFilter = status?.takeIf { it.isNotBlank() }?.let {
            runCatching { com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.valueOf(it.uppercase()) }.getOrNull()
        }
        val filter = ValidationFilterDTO(
            onlyMismatches = onlyMismatches,
            onlyStatusDrift = onlyStatusDrift,
            onlyMissingOrNew = onlyMissingOrNew,
            statusFilter = statusFilter
        )
        val diff = crawlConfigValidationService.getFolderDiff(id, folderId, filter)
        model.addAttribute("diff", diff)
        model.addAttribute("crawlConfigId", id)
        return "crawlConfig/fragments/validation :: diffPanel"
    }

    @PostMapping("/{id}/validate/folders/{folderId}/baseline/capture")
    fun captureFolderBaseline(
        @PathVariable(name = "id") id: Long,
        @PathVariable(name = "folderId") folderId: Long,
        @RequestParam(name = "maxSamples", required = false, defaultValue = "50") maxSamples: Int,
        @RequestParam(name = "policy", required = false, defaultValue = "REPRESENTATIVE_50") policy: String,
        @RequestParam(name = "seed", required = false) seed: String?,
        model: Model
    ): String {
        val samplingPolicy = runCatching {
            BaselineSamplingPolicy.valueOf(policy.uppercase())
        }.getOrDefault(BaselineSamplingPolicy.REPRESENTATIVE_50)

        crawlConfigValidationService.captureFolderBaseline(
            crawlConfigId = id,
            folderId = folderId,
            request = com.oconeco.spring_search_tempo.web.model.BaselineCaptureRequestDTO(
                maxSamples = maxSamples,
                samplingPolicy = samplingPolicy,
                seed = seed
            )
        )

        model.addAttribute("crawlConfigId", id)
        model.addAttribute("validationSummaries", crawlConfigValidationService.getFolderSummaries(id))
        return "crawlConfig/fragments/validation :: folderSummaryTable"
    }

    @PostMapping("/{id}/validate/folders/{folderId}/baseline/clear")
    fun clearFolderBaseline(
        @PathVariable(name = "id") id: Long,
        @PathVariable(name = "folderId") folderId: Long,
        model: Model
    ): String {
        crawlConfigValidationService.clearFolderBaseline(id, folderId)
        model.addAttribute("crawlConfigId", id)
        model.addAttribute("validationSummaries", crawlConfigValidationService.getFolderSummaries(id))
        return "crawlConfig/fragments/validation :: folderSummaryTable"
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
        model.addAttribute("folderPatternsSemanticText", jsonToText(crawlConfig.folderPatternsSemantic))
        model.addAttribute("filePatternsSkipText", jsonToText(crawlConfig.filePatternsSkip))
        model.addAttribute("filePatternsLocateText", jsonToText(crawlConfig.filePatternsLocate))
        model.addAttribute("filePatternsIndexText", jsonToText(crawlConfig.filePatternsIndex))
        model.addAttribute("filePatternsAnalyzeText", jsonToText(crawlConfig.filePatternsAnalyze))
        model.addAttribute("filePatternsSemanticText", jsonToText(crawlConfig.filePatternsSemantic))

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
        @RequestParam(name = "folderPatternsSemanticText", required = false) folderPatternsSemanticText: String?,
        @RequestParam(name = "filePatternsSkipText", required = false) filePatternsSkipText: String?,
        @RequestParam(name = "filePatternsLocateText", required = false) filePatternsLocateText: String?,
        @RequestParam(name = "filePatternsIndexText", required = false) filePatternsIndexText: String?,
        @RequestParam(name = "filePatternsAnalyzeText", required = false) filePatternsAnalyzeText: String?,
        @RequestParam(name = "filePatternsSemanticText", required = false) filePatternsSemanticText: String?,
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
        crawlConfigDTO.folderPatternsSemantic = textToJson(folderPatternsSemanticText)
        crawlConfigDTO.filePatternsSkip = textToJson(filePatternsSkipText)
        crawlConfigDTO.filePatternsLocate = textToJson(filePatternsLocateText)
        crawlConfigDTO.filePatternsIndex = textToJson(filePatternsIndexText)
        crawlConfigDTO.filePatternsAnalyze = textToJson(filePatternsAnalyzeText)
        crawlConfigDTO.filePatternsSemantic = textToJson(filePatternsSemanticText)

        if (bindingResult.hasErrors()) {
            model.addAttribute("startPathsText", startPathsText)
            model.addAttribute("folderPatternsSkipText", folderPatternsSkipText)
            model.addAttribute("folderPatternsLocateText", folderPatternsLocateText)
            model.addAttribute("folderPatternsIndexText", folderPatternsIndexText)
            model.addAttribute("folderPatternsAnalyzeText", folderPatternsAnalyzeText)
            model.addAttribute("folderPatternsSemanticText", folderPatternsSemanticText)
            model.addAttribute("filePatternsSkipText", filePatternsSkipText)
            model.addAttribute("filePatternsLocateText", filePatternsLocateText)
            model.addAttribute("filePatternsIndexText", filePatternsIndexText)
            model.addAttribute("filePatternsAnalyzeText", filePatternsAnalyzeText)
            model.addAttribute("filePatternsSemanticText", filePatternsSemanticText)
            return "crawlConfig/edit"
        }

        try {
            crawlConfigService.update(id, crawlConfigDTO)
            redirectAttributes.addFlashAttribute("message",
                "Configuration updated: ${crawlConfigDTO.label ?: crawlConfigDTO.name}")
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
                "Crawl job started for configuration: ${crawlConfig.label ?: crawlConfig.name}$optionsText")
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

    // ========== Inline Field Editing ==========

    /**
     * Show edit form for a specific field.
     */
    @GetMapping("/{id}/field/{field}/edit")
    fun fieldEdit(
        @PathVariable(name = "id") id: Long,
        @PathVariable(name = "field") field: String,
        model: Model
    ): String {
        val config = crawlConfigService.get(id)
        model.addAttribute("id", id)
        model.addAttribute("field", field)
        model.addAttribute("knownHosts", crawlConfigService.findDistinctSourceHosts())

        return when (field) {
            "name" -> {
                model.addAttribute("value", config.name)
                model.addAttribute("label", "Name")
                "crawlConfig/fragments/inlineEdit :: textEdit"
            }
            "label" -> {
                model.addAttribute("value", config.label)
                model.addAttribute("label", "Label")
                "crawlConfig/fragments/inlineEdit :: textEdit"
            }
            "maxDepth" -> {
                model.addAttribute("value", config.maxDepth)
                model.addAttribute("label", "Max Depth")
                model.addAttribute("min", 1)
                model.addAttribute("max", 100)
                "crawlConfig/fragments/inlineEdit :: numberEdit"
            }
            "freshnessHours" -> {
                model.addAttribute("value", config.freshnessHours)
                model.addAttribute("label", "Freshness Hours")
                model.addAttribute("min", 1)
                model.addAttribute("max", 8760)
                "crawlConfig/fragments/inlineEdit :: numberEdit"
            }
            "sourceHost" -> {
                model.addAttribute("value", config.sourceHost)
                "crawlConfig/fragments/inlineEdit :: hostEdit"
            }
            "startPaths" -> {
                model.addAttribute("pathsText", config.startPaths?.joinToString("\n") ?: "")
                "crawlConfig/fragments/inlineEdit :: pathsEdit"
            }
            // Pattern fields
            "folderPatternsSkip" -> {
                model.addAttribute("patternsText", jsonToText(config.folderPatternsSkip))
                model.addAttribute("badgeClass", "bg-secondary")
                model.addAttribute("badgeLabel", "SKIP")
                "crawlConfig/fragments/inlineEdit :: patternEdit"
            }
            "folderPatternsLocate" -> {
                model.addAttribute("patternsText", jsonToText(config.folderPatternsLocate))
                model.addAttribute("badgeClass", "bg-info")
                model.addAttribute("badgeLabel", "LOCATE")
                "crawlConfig/fragments/inlineEdit :: patternEdit"
            }
            "folderPatternsIndex" -> {
                model.addAttribute("patternsText", jsonToText(config.folderPatternsIndex))
                model.addAttribute("badgeClass", "bg-primary")
                model.addAttribute("badgeLabel", "INDEX")
                "crawlConfig/fragments/inlineEdit :: patternEdit"
            }
            "folderPatternsAnalyze" -> {
                model.addAttribute("patternsText", jsonToText(config.folderPatternsAnalyze))
                model.addAttribute("badgeClass", "bg-success")
                model.addAttribute("badgeLabel", "ANALYZE")
                "crawlConfig/fragments/inlineEdit :: patternEdit"
            }
            "filePatternsSkip" -> {
                model.addAttribute("patternsText", jsonToText(config.filePatternsSkip))
                model.addAttribute("badgeClass", "bg-secondary")
                model.addAttribute("badgeLabel", "SKIP")
                "crawlConfig/fragments/inlineEdit :: patternEdit"
            }
            "filePatternsLocate" -> {
                model.addAttribute("patternsText", jsonToText(config.filePatternsLocate))
                model.addAttribute("badgeClass", "bg-info")
                model.addAttribute("badgeLabel", "LOCATE")
                "crawlConfig/fragments/inlineEdit :: patternEdit"
            }
            "filePatternsIndex" -> {
                model.addAttribute("patternsText", jsonToText(config.filePatternsIndex))
                model.addAttribute("badgeClass", "bg-primary")
                model.addAttribute("badgeLabel", "INDEX")
                "crawlConfig/fragments/inlineEdit :: patternEdit"
            }
            "filePatternsAnalyze" -> {
                model.addAttribute("patternsText", jsonToText(config.filePatternsAnalyze))
                model.addAttribute("badgeClass", "bg-success")
                model.addAttribute("badgeLabel", "ANALYZE")
                "crawlConfig/fragments/inlineEdit :: patternEdit"
            }
            else -> throw IllegalArgumentException("Unknown field: $field")
        }
    }

    /**
     * Show display mode for a specific field (cancel edit).
     */
    @GetMapping("/{id}/field/{field}/display")
    fun fieldDisplay(
        @PathVariable(name = "id") id: Long,
        @PathVariable(name = "field") field: String,
        model: Model
    ): String {
        val config = crawlConfigService.get(id)
        model.addAttribute("id", id)
        model.addAttribute("field", field)

        return when (field) {
            "name" -> {
                model.addAttribute("value", config.name)
                model.addAttribute("label", "Name")
                "crawlConfig/fragments/inlineEdit :: textDisplay"
            }
            "label" -> {
                model.addAttribute("value", config.label)
                model.addAttribute("label", "Label")
                "crawlConfig/fragments/inlineEdit :: textDisplay"
            }
            "maxDepth" -> {
                model.addAttribute("value", config.maxDepth)
                model.addAttribute("label", "Max Depth")
                model.addAttribute("defaultText", "not set")
                "crawlConfig/fragments/inlineEdit :: numberDisplay"
            }
            "freshnessHours" -> {
                model.addAttribute("value", config.freshnessHours)
                model.addAttribute("label", "Freshness Hours")
                model.addAttribute("defaultText", "Default (24)")
                "crawlConfig/fragments/inlineEdit :: numberDisplay"
            }
            "sourceHost" -> {
                model.addAttribute("value", config.sourceHost)
                model.addAttribute("label", "Host")
                "crawlConfig/fragments/inlineEdit :: hostDisplay"
            }
            "startPaths" -> {
                model.addAttribute("paths", config.startPaths)
                "crawlConfig/fragments/inlineEdit :: pathsDisplay"
            }
            "followLinks" -> {
                model.addAttribute("value", config.followLinks ?: false)
                model.addAttribute("trueLabel", "true")
                model.addAttribute("falseLabel", "false")
                "crawlConfig/fragments/inlineEdit :: booleanDisplay"
            }
            "parallel" -> {
                model.addAttribute("value", config.parallel ?: false)
                model.addAttribute("trueLabel", "true")
                model.addAttribute("falseLabel", "false")
                "crawlConfig/fragments/inlineEdit :: booleanDisplay"
            }
            // Pattern fields
            "folderPatternsSkip" -> {
                model.addAttribute("patternsText", jsonToText(config.folderPatternsSkip))
                model.addAttribute("badgeClass", "bg-secondary")
                model.addAttribute("badgeLabel", "SKIP")
                "crawlConfig/fragments/inlineEdit :: patternDisplay"
            }
            "folderPatternsLocate" -> {
                model.addAttribute("patternsText", jsonToText(config.folderPatternsLocate))
                model.addAttribute("badgeClass", "bg-info")
                model.addAttribute("badgeLabel", "LOCATE")
                "crawlConfig/fragments/inlineEdit :: patternDisplay"
            }
            "folderPatternsIndex" -> {
                model.addAttribute("patternsText", jsonToText(config.folderPatternsIndex))
                model.addAttribute("badgeClass", "bg-primary")
                model.addAttribute("badgeLabel", "INDEX")
                "crawlConfig/fragments/inlineEdit :: patternDisplay"
            }
            "folderPatternsAnalyze" -> {
                model.addAttribute("patternsText", jsonToText(config.folderPatternsAnalyze))
                model.addAttribute("badgeClass", "bg-success")
                model.addAttribute("badgeLabel", "ANALYZE")
                "crawlConfig/fragments/inlineEdit :: patternDisplay"
            }
            "filePatternsSkip" -> {
                model.addAttribute("patternsText", jsonToText(config.filePatternsSkip))
                model.addAttribute("badgeClass", "bg-secondary")
                model.addAttribute("badgeLabel", "SKIP")
                "crawlConfig/fragments/inlineEdit :: patternDisplay"
            }
            "filePatternsLocate" -> {
                model.addAttribute("patternsText", jsonToText(config.filePatternsLocate))
                model.addAttribute("badgeClass", "bg-info")
                model.addAttribute("badgeLabel", "LOCATE")
                "crawlConfig/fragments/inlineEdit :: patternDisplay"
            }
            "filePatternsIndex" -> {
                model.addAttribute("patternsText", jsonToText(config.filePatternsIndex))
                model.addAttribute("badgeClass", "bg-primary")
                model.addAttribute("badgeLabel", "INDEX")
                "crawlConfig/fragments/inlineEdit :: patternDisplay"
            }
            "filePatternsAnalyze" -> {
                model.addAttribute("patternsText", jsonToText(config.filePatternsAnalyze))
                model.addAttribute("badgeClass", "bg-success")
                model.addAttribute("badgeLabel", "ANALYZE")
                "crawlConfig/fragments/inlineEdit :: patternDisplay"
            }
            else -> throw IllegalArgumentException("Unknown field: $field")
        }
    }

    /**
     * Update a specific field value (PATCH).
     */
    @PatchMapping("/{id}/field/{field}")
    fun fieldUpdate(
        @PathVariable(name = "id") id: Long,
        @PathVariable(name = "field") field: String,
        @RequestParam(required = false) value: String?,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) label: String?,
        @RequestParam(required = false) maxDepth: Int?,
        @RequestParam(required = false) freshnessHours: Int?,
        @RequestParam(required = false) sourceHost: String?,
        @RequestParam(required = false) startPaths: String?,
        @RequestParam(required = false) folderPatternsSkip: String?,
        @RequestParam(required = false) folderPatternsLocate: String?,
        @RequestParam(required = false) folderPatternsIndex: String?,
        @RequestParam(required = false) folderPatternsAnalyze: String?,
        @RequestParam(required = false) filePatternsSkip: String?,
        @RequestParam(required = false) filePatternsLocate: String?,
        @RequestParam(required = false) filePatternsIndex: String?,
        @RequestParam(required = false) filePatternsAnalyze: String?,
        model: Model
    ): String {
        val config = crawlConfigService.get(id)

        // Update the field based on which one was submitted
        when (field) {
            "name" -> config.name = name?.trim()?.takeIf { it.isNotBlank() }
            "label" -> config.label = label?.trim()?.takeIf { it.isNotBlank() }
            "maxDepth" -> config.maxDepth = maxDepth
            "freshnessHours" -> config.freshnessHours = freshnessHours
            "sourceHost" -> config.sourceHost = sourceHost?.trim()?.takeIf { it.isNotBlank() }
            "followLinks" -> config.followLinks = value?.toBoolean() ?: !(config.followLinks ?: false)
            "parallel" -> config.parallel = value?.toBoolean() ?: !(config.parallel ?: false)
            "startPaths" -> {
                config.startPaths = startPaths
                    ?.split("\n")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            }
            // Pattern fields - convert newline-separated text to JSON array
            "folderPatternsSkip" -> config.folderPatternsSkip = textToJson(folderPatternsSkip)
            "folderPatternsLocate" -> config.folderPatternsLocate = textToJson(folderPatternsLocate)
            "folderPatternsIndex" -> config.folderPatternsIndex = textToJson(folderPatternsIndex)
            "folderPatternsAnalyze" -> config.folderPatternsAnalyze = textToJson(folderPatternsAnalyze)
            "filePatternsSkip" -> config.filePatternsSkip = textToJson(filePatternsSkip)
            "filePatternsLocate" -> config.filePatternsLocate = textToJson(filePatternsLocate)
            "filePatternsIndex" -> config.filePatternsIndex = textToJson(filePatternsIndex)
            "filePatternsAnalyze" -> config.filePatternsAnalyze = textToJson(filePatternsAnalyze)
            else -> throw IllegalArgumentException("Unknown field: $field")
        }

        // Persist the update
        crawlConfigService.update(id, config)

        // Return the display fragment
        return fieldDisplay(id, field, model)
    }

    @PostMapping("/{id}/delete-data")
    fun deleteData(
        @PathVariable(name = "id") id: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        val crawlConfig = crawlConfigService.get(id)
        val summary = cleanupService.deleteAllDataForCrawlConfig(id)

        if (summary.isEmpty) {
            redirectAttributes.addFlashAttribute("message", "No data to delete for '${crawlConfig.label ?: crawlConfig.name}'")
        } else {
            redirectAttributes.addFlashAttribute(
                "message",
                "Deleted ${summary.filesDeleted} files, ${summary.foldersDeleted} folders, and ${summary.chunksDeleted} chunks for '${crawlConfig.label ?: crawlConfig.name}'"
            )
        }
        return "redirect:/crawlConfigs/$id"
    }

    @PostMapping("/{id}/delete")
    fun delete(
        @PathVariable(name = "id") id: Long,
        @RequestParam(name = "redirectTo", required = false) redirectTo: String?,
        redirectAttributes: RedirectAttributes
    ): String {
        return try {
            val summary = smartDeleteService.deleteCrawlConfig(id)
            redirectAttributes.addFlashAttribute(
                "message",
                "Deleted crawl config '${summary.crawlConfigName}' with ${summary.filesDeleted} files, ${summary.foldersDeleted} folders, ${summary.chunksDeleted} chunks, ${summary.jobRunsDeleted} job runs, and ${summary.remoteTasksDeleted} remote tasks."
            )
            safeRedirect(redirectTo, "/crawlConfigs")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error", "Delete failed: ${e.message}")
            safeRedirect(redirectTo, "/crawlConfigs")
        }
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
                started.add(crawlConfig.label ?: crawlConfig.name ?: "Config #$id")
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

    private fun ensureHostAccess(host: String) {
        if (userOwnershipService.isCurrentUserAdmin()) {
            return
        }
        val owned = userOwnershipService.getCurrentUserSourceHosts()
        if (owned.isNotEmpty() && owned.none { it.equals(host, ignoreCase = true) }) {
            throw IllegalArgumentException("Access denied for source host '$host'")
        }
    }

    private fun urlEncode(value: String?): String =
        java.net.URLEncoder.encode(value?.trim().orEmpty(), Charsets.UTF_8)

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

    private fun listToJson(patterns: List<String>): String? {
        if (patterns.isEmpty()) return null
        return objectMapper.writeValueAsString(patterns)
    }

    private fun safeRedirect(redirectTo: String?, fallback: String): String {
        val target = redirectTo?.trim()?.takeIf { it.startsWith("/") } ?: fallback
        return "redirect:$target"
    }

    private fun buildPresetBundle(os: WizardOs): List<WizardPresetConfig> {
        val prefix = os.prefix
        val userHome = defaultUserHomeForOs(os)
        val docsPath = joinPath(os, userHome, "Documents")
        val picturesPath = joinPath(os, userHome, "Pictures")
        val videosPath = joinPath(os, userHome, if (os == WizardOs.MACOS) "Movies" else "Videos")
        val desktopPath = joinPath(os, userHome, "Desktop")

        return listOf(
            WizardPresetConfig(
                name = "${prefix}_USER_DOCS",
                label = "User Docs",
                description = "Primary user documents: index + NLP for text-heavy content.",
                startPaths = listOf(docsPath),
                maxDepth = 12,
                folderSkipPatterns = commonRepoAndBuildSkipPatterns() + commonCacheAndTempFolderSkipPatterns(os),
                folderIndexPatterns = listOf(".*"),
                fileSkipPatterns = commonTempFileSkipPatterns(),
                fileLocatePatterns = mediaLocateFilePatterns() + archiveLocateFilePatterns(),
                fileIndexPatterns = documentIndexFilePatterns(),
                fileAnalyzePatterns = documentAnalyzeFilePatterns()
            ),
            WizardPresetConfig(
                name = "${prefix}_USER_PICTURES",
                label = "User Pictures",
                description = "Image library for fast locate/search by metadata.",
                startPaths = listOf(picturesPath),
                maxDepth = 10,
                folderSkipPatterns = commonRepoAndBuildSkipPatterns() + commonCacheAndTempFolderSkipPatterns(os),
                folderLocatePatterns = listOf(".*"),
                fileSkipPatterns = commonTempFileSkipPatterns(),
                fileLocatePatterns = imageLocateFilePatterns()
            ),
            WizardPresetConfig(
                name = "${prefix}_USER_VIDEOS",
                label = "User Videos",
                description = "Video/media library for locate metadata coverage.",
                startPaths = listOf(videosPath),
                maxDepth = 10,
                folderSkipPatterns = commonRepoAndBuildSkipPatterns() + commonCacheAndTempFolderSkipPatterns(os),
                folderLocatePatterns = listOf(".*"),
                fileSkipPatterns = commonTempFileSkipPatterns(),
                fileLocatePatterns = videoLocateFilePatterns()
            ),
            WizardPresetConfig(
                name = "${prefix}_USER_DESKTOP",
                label = "User Desktop",
                description = "Desktop workspace with practical index + analyze defaults.",
                startPaths = listOf(desktopPath),
                maxDepth = 8,
                folderSkipPatterns = commonRepoAndBuildSkipPatterns() + commonCacheAndTempFolderSkipPatterns(os),
                folderIndexPatterns = listOf(".*"),
                fileSkipPatterns = commonTempFileSkipPatterns(),
                fileLocatePatterns = mediaLocateFilePatterns() + archiveLocateFilePatterns(),
                fileIndexPatterns = documentIndexFilePatterns() + codeIndexFilePatterns(),
                fileAnalyzePatterns = documentAnalyzeFilePatterns()
            ),
            WizardPresetConfig(
                name = "${prefix}_WORK",
                label = "Work Projects",
                description = "Project/work trees with source/config indexing and NLP for docs.",
                startPaths = defaultWorkPathsForOs(os, userHome),
                maxDepth = 20,
                folderSkipPatterns = commonRepoAndBuildSkipPatterns() + commonCacheAndTempFolderSkipPatterns(os),
                folderIndexPatterns = listOf(".*"),
                fileSkipPatterns = commonTempFileSkipPatterns(),
                fileLocatePatterns = mediaLocateFilePatterns() + archiveLocateFilePatterns() + binaryLocateFilePatterns(),
                fileIndexPatterns = codeIndexFilePatterns() + configIndexFilePatterns(os) + documentIndexFilePatterns(),
                fileAnalyzePatterns = documentAnalyzeFilePatterns()
            ),
            WizardPresetConfig(
                name = "${prefix}_USER_CONF",
                label = "User Config",
                description = "User-level application and shell configuration paths.",
                startPaths = defaultUserConfigPathsForOs(os, userHome),
                maxDepth = 10,
                folderSkipPatterns = commonCacheAndTempFolderSkipPatterns(os),
                folderIndexPatterns = listOf(".*"),
                fileSkipPatterns = commonTempFileSkipPatterns() + secretFileSkipPatterns(os),
                fileIndexPatterns = configIndexFilePatterns(os) + scriptIndexFilePatterns(os)
            ),
            WizardPresetConfig(
                name = "${prefix}_OS_CONF",
                label = "OS Config",
                description = "System configuration files with sensitive paths excluded.",
                startPaths = defaultOsConfigPathsForOs(os),
                maxDepth = 8,
                enabledByDefault = false,
                folderSkipPatterns = osSensitiveConfigFolderSkipPatterns(os),
                folderLocatePatterns = listOf(".*"),
                fileSkipPatterns = commonTempFileSkipPatterns() + secretFileSkipPatterns(os),
                fileIndexPatterns = configIndexFilePatterns(os) + osServiceFilePatterns(os)
            ),
            WizardPresetConfig(
                name = "${prefix}_OS_LOGS",
                label = "OS Logs",
                description = "System/application logs for keyword search (index only, no NLP).",
                startPaths = defaultOsLogPathsForOs(os),
                maxDepth = 10,
                folderSkipPatterns = osLogFolderSkipPatterns(os),
                folderIndexPatterns = listOf(".*"),
                fileSkipPatterns = commonTempFileSkipPatterns() + compressedLogSkipPatterns(os),
                fileLocatePatterns = listOf(".*"),
                fileIndexPatterns = logIndexFilePatterns(os)
            ),
            WizardPresetConfig(
                name = "${prefix}_OS_BIN_INFO",
                label = "OS Bin + Man",
                description = "Executables and manuals: locate all, index text manuals/scripts.",
                startPaths = defaultOsBinInfoPathsForOs(os),
                maxDepth = 8,
                enabledByDefault = false,
                folderLocatePatterns = listOf(".*"),
                fileLocatePatterns = listOf(".*"),
                fileIndexPatterns = scriptIndexFilePatterns(os) + manInfoIndexFilePatterns(os)
            ),
            WizardPresetConfig(
                name = "${prefix}_OS_ALL",
                label = "OS All (Locate)",
                description = "Full-system locate coverage with aggressive skips for pseudo-fs, caches, and build trees.",
                startPaths = listOf(rootStartPathForOs(os)),
                maxDepth = 24,
                folderSkipPatterns = commonRepoAndBuildSkipPatterns() + commonCacheAndTempFolderSkipPatterns(os) + osRootSkipPatterns(os),
                folderLocatePatterns = listOf(".*"),
                fileSkipPatterns = commonTempFileSkipPatterns(),
                fileLocatePatterns = listOf(".*")
            )
        )
    }

    private fun defaultUserHomeForOs(os: WizardOs): String {
        val currentOs = System.getProperty("os.name", "").lowercase()
        val currentHome = System.getProperty("user.home").orEmpty()
        val currentUser = System.getProperty("user.name", "user")

        return when (os) {
            WizardOs.LINUX -> if (currentOs.contains("linux") && currentHome.startsWith("/")) {
                currentHome
            } else {
                "/home/$currentUser"
            }
            WizardOs.MACOS -> if (currentOs.contains("mac") && currentHome.startsWith("/")) {
                currentHome
            } else {
                "/Users/$currentUser"
            }
            WizardOs.WINDOWS -> if (currentOs.contains("win") && currentHome.contains("\\")) {
                currentHome
            } else {
                "C:\\Users\\$currentUser"
            }
        }
    }

    private fun joinPath(os: WizardOs, base: String, child: String): String {
        val separator = if (os == WizardOs.WINDOWS) "\\" else "/"
        val normalizedBase = base.trimEnd('/', '\\')
        val normalizedChild = child.trimStart('/', '\\')
        return "$normalizedBase$separator$normalizedChild"
    }

    private fun rootStartPathForOs(os: WizardOs): String =
        if (os == WizardOs.WINDOWS) "C:\\" else "/"

    private fun defaultWorkPathsForOs(os: WizardOs, userHome: String): List<String> = when (os) {
        WizardOs.LINUX -> listOf("/opt/work", joinPath(os, userHome, "work"), joinPath(os, userHome, "Projects"))
        WizardOs.MACOS -> listOf(joinPath(os, userHome, "Projects"), "/Users/Shared/work", "/opt/work")
        WizardOs.WINDOWS -> listOf("C:\\Work", "C:\\Projects", "C:\\Dev", joinPath(os, userHome, "source"), joinPath(os, userHome, "workspace"))
    }

    private fun defaultUserConfigPathsForOs(os: WizardOs, userHome: String): List<String> = when (os) {
        WizardOs.LINUX -> listOf(joinPath(os, userHome, ".config"))
        WizardOs.MACOS -> listOf(joinPath(os, userHome, "Library/Preferences"), joinPath(os, userHome, "Library/Application Support"))
        WizardOs.WINDOWS -> listOf(joinPath(os, userHome, "AppData\\Roaming"), joinPath(os, userHome, "AppData\\Local"))
    }

    private fun defaultOsConfigPathsForOs(os: WizardOs): List<String> = when (os) {
        WizardOs.LINUX -> listOf("/etc")
        WizardOs.MACOS -> listOf("/etc", "/private/etc")
        WizardOs.WINDOWS -> listOf("C:\\Windows\\System32\\drivers\\etc", "C:\\ProgramData")
    }

    private fun defaultOsLogPathsForOs(os: WizardOs): List<String> = when (os) {
        WizardOs.LINUX -> listOf("/var/log")
        WizardOs.MACOS -> listOf("/var/log", "/Library/Logs")
        WizardOs.WINDOWS -> listOf("C:\\Windows\\Logs", "C:\\Windows\\System32\\winevt\\Logs")
    }

    private fun defaultOsBinInfoPathsForOs(os: WizardOs): List<String> = when (os) {
        WizardOs.LINUX -> listOf(
            "/bin", "/sbin", "/usr/bin", "/usr/sbin", "/usr/local/bin", "/usr/local/sbin",
            "/usr/share/man", "/usr/local/share/man", "/usr/share/info"
        )
        WizardOs.MACOS -> listOf(
            "/bin", "/sbin", "/usr/bin", "/usr/sbin", "/usr/local/bin", "/opt/homebrew/bin",
            "/usr/share/man", "/usr/local/share/man", "/opt/homebrew/share/man"
        )
        WizardOs.WINDOWS -> listOf(
            "C:\\Windows\\System32", "C:\\Windows", "C:\\Program Files", "C:\\Program Files (x86)", "C:\\ProgramData\\chocolatey\\bin"
        )
    }

    private fun commonRepoAndBuildSkipPatterns(): List<String> = listOf(
        """.*[\\/]\.git[\\/].*""",
        """.*[\\/]\.hg[\\/].*""",
        """.*[\\/]\.svn[\\/].*""",
        """.*[\\/](node_modules|vendor|bower_components|\.yarn|\.pnpm-store)[\\/].*""",
        """.*[\\/](build|dist|target|out|coverage|\.next|\.nuxt|\.svelte-kit)[\\/].*""",
        """.*[\\/](\.gradle|\.m2|\.ivy2|\.cargo|\.npm|\.cache|\.venv|venv|\.tox)[\\/].*""",
        """.*[\\/](__pycache__|\.pytest_cache|\.mypy_cache|\.ruff_cache)[\\/].*""",
        """.*[\\/](\.idea|\.vscode|\.vs|\.history)[\\/].*"""
    )

    private fun commonCacheAndTempFolderSkipPatterns(os: WizardOs): List<String> {
        val common = mutableListOf(
            """.*[\\/](tmp|temp)[\\/].*""",
            """.*[\\/]\.Trash[\\/].*"""
        )
        when (os) {
            WizardOs.LINUX -> common.addAll(listOf(""".*[\\/]\.local[\\/]share[\\/]Trash[\\/].*""", """.*[\\/]snap[\\/].*"""))
            WizardOs.MACOS -> common.addAll(listOf(""".*[\\/]Library[\\/]Caches[\\/].*""", """.*[\\/]private[\\/]var[\\/]folders[\\/].*"""))
            WizardOs.WINDOWS -> common.addAll(listOf(""".*[\\/]AppData[\\/]Local[\\/]Temp[\\/].*""", """.*[\\/][$]Recycle\.Bin[\\/].*"""))
        }
        return common
    }

    private fun osRootSkipPatterns(os: WizardOs): List<String> = when (os) {
        WizardOs.LINUX -> listOf(
            """^/proc/.*""",
            """^/sys/.*""",
            """^/dev/.*""",
            """^/run/.*""",
            """^/tmp/.*""",
            """^/mnt/.*""",
            """^/media/.*""",
            """^/lost\+found/.*""",
            """^/var/cache/.*""",
            """^/var/lib/docker/.*""",
            """^/var/lib/flatpak/.*"""
        )
        WizardOs.MACOS -> listOf(
            """^/System/Volumes/.*""",
            """^/private/var/vm/.*""",
            """^/private/var/folders/.*""",
            """^/Volumes/.*""",
            """^/dev/.*"""
        )
        WizardOs.WINDOWS -> listOf(
            """(?i)^[a-z]:[\\/](Windows[\\/](WinSxS|Temp|SoftwareDistribution)[\\/].*)""",
            """(?i).*[\\/]System Volume Information[\\/].*""",
            """(?i).*[\\/][$]Recycle\.Bin[\\/].*""",
            """(?i)^[a-z]:[\\/]ProgramData[\\/]Package Cache[\\/].*""",
            """(?i)^[a-z]:[\\/]Users[\\/][^\\/]+[\\/]AppData[\\/]Local[\\/]Temp[\\/].*"""
        )
    }

    private fun commonTempFileSkipPatterns(): List<String> = listOf(
        """.*\.(tmp|temp|swp|swo|bak|old|orig|part|crdownload|download|lck|lock)$""",
        """.*~$"""
    )

    private fun secretFileSkipPatterns(os: WizardOs): List<String> {
        val common = mutableListOf(
            """.*(id_rsa|id_dsa|id_ed25519)(\.pub)?$""",
            """.*\.(pem|p12|pfx|key|kdbx)$""",
            """.*/(\.env|\.env\..*)$""",
            """.*/(shadow|gshadow|passwd-)$""",
            """.*/(known_hosts|authorized_keys)$"""
        )
        if (os == WizardOs.WINDOWS) {
            common.add("""(?i).*[\\/](SAM|SECURITY|SYSTEM|NTUSER\.DAT)$""")
        }
        return common
    }

    private fun compressedLogSkipPatterns(os: WizardOs): List<String> {
        val patterns = mutableListOf(
            """.*\.(gz|bz2|xz|zst|zip)$"""
        )
        if (os == WizardOs.WINDOWS) {
            patterns.add("""(?i).*\.evtx$""")
        }
        return patterns
    }

    private fun osSensitiveConfigFolderSkipPatterns(os: WizardOs): List<String> = when (os) {
        WizardOs.LINUX -> listOf(""".*/(ssl|pki|ssh|openvpn)/.*""")
        WizardOs.MACOS -> listOf(""".*/(Keychains|PrivateFrameworks)/.*""")
        WizardOs.WINDOWS -> listOf("""(?i).*[\\/](Microsoft[\\/]Crypto|System32[\\/]config)[\\/].*""")
    }

    private fun osLogFolderSkipPatterns(os: WizardOs): List<String> = when (os) {
        WizardOs.LINUX -> listOf(""".*/journal/.*""")
        WizardOs.MACOS -> listOf(""".*/DiagnosticReports/.*""")
        WizardOs.WINDOWS -> listOf("""(?i).*[\\/]CBS[\\/].*""")
    }

    private fun imageLocateFilePatterns(): List<String> = listOf(
        """.*\.(jpg|jpeg|png|gif|bmp|svg|webp|heic|heif|tiff|tif|raw|cr2|nef|arw|dng)$"""
    )

    private fun videoLocateFilePatterns(): List<String> = listOf(
        """.*\.(mp4|avi|mov|mkv|flv|wmv|webm|m4v|mpeg|mpg|3gp|ogv|mp3|m4a|aac|flac|wav|ogg)$"""
    )

    private fun mediaLocateFilePatterns(): List<String> =
        imageLocateFilePatterns() + videoLocateFilePatterns()

    private fun archiveLocateFilePatterns(): List<String> = listOf(
        """.*\.(zip|tar|gz|bz2|xz|7z|rar|iso|dmg|img|cab)$"""
    )

    private fun binaryLocateFilePatterns(): List<String> = listOf(
        """.*\.(exe|msi|dll|sys|so|dylib|class|pyc|jar|war|ear)$"""
    )

    private fun codeIndexFilePatterns(): List<String> = listOf(
        """.*\.(kt|kts|java|scala)$""",
        """.*\.(py|pyi)$""",
        """.*\.(js|ts|jsx|tsx|mjs|cjs|vue|svelte)$""",
        """.*\.(go|rs|c|cpp|h|hpp|cc|cxx|hxx)$""",
        """.*\.(rb|php|swift|m|mm|cs|fs|vb)$""",
        """.*\.(sh|bash|zsh|fish|ps1|cmd|bat)$""",
        """.*\.sql$""",
        """.*/(Dockerfile|docker-compose.*\.ya?ml)$""",
        """.*/(Makefile|CMakeLists\.txt|Rakefile|Gemfile)$""",
        """.*/(package\.json|requirements\.txt|Pipfile|poetry\.lock)$""",
        """.*/(build\.gradle.*|settings\.gradle.*|pom\.xml|go\.mod|Cargo\.toml)$"""
    )

    private fun configIndexFilePatterns(os: WizardOs): List<String> {
        val common = mutableListOf(
            """.*\.(xml|json|ya?ml|toml|properties|conf|config|ini)$""",
            """.*/\.(bashrc|zshrc|profile|gitconfig|npmrc|yarnrc)$""",
            """.*/(hosts|hostname|fstab|resolv\.conf)$"""
        )
        when (os) {
            WizardOs.LINUX, WizardOs.MACOS -> common.add(""".*/.*\.d/.*""")
            WizardOs.WINDOWS -> common.addAll(listOf("""(?i).*\.reg$""", """(?i).*[\\/]hosts$"""))
        }
        return common
    }

    private fun documentIndexFilePatterns(): List<String> = listOf(
        """.*\.(txt|md|rst|adoc|org)$""",
        """.*\.(pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$""",
        """.*\.(epub|mobi)$""",
        """.*\.html?$"""
    )

    private fun documentAnalyzeFilePatterns(): List<String> = listOf(
        """.*\.(md|txt|org|rst|adoc)$""",
        """.*\.(pdf|docx?|xlsx?|pptx?|odt|rtf|csv)$""",
        """.*\.(epub|mobi)$""",
        """.*\.html?$""",
        """.*/(README|CONTRIBUTING|CHANGELOG|LICENSE|AUTHORS|NOTICE|TODO).*$"""
    )

    private fun scriptIndexFilePatterns(os: WizardOs): List<String> = when (os) {
        WizardOs.WINDOWS -> listOf(""".*\.(ps1|cmd|bat|vbs)$""")
        WizardOs.LINUX, WizardOs.MACOS -> listOf(""".*\.(sh|bash|zsh|fish)$""")
    }

    private fun osServiceFilePatterns(os: WizardOs): List<String> = when (os) {
        WizardOs.LINUX -> listOf(
            """.*\.service$""",
            """.*\.socket$""",
            """.*\.timer$""",
            """.*\.mount$""",
            """.*\.target$"""
        )
        WizardOs.MACOS -> listOf(
            """.*\.plist$""",
            """.*/Launch(Agents|Daemons)/.*"""
        )
        WizardOs.WINDOWS -> listOf(
            """(?i).*\.ini$""",
            """(?i).*\.xml$"""
        )
    }

    private fun logIndexFilePatterns(os: WizardOs): List<String> = when (os) {
        WizardOs.LINUX -> listOf(
            """.*\.log$""",
            """.*\.log\.[0-9]+$""",
            """.*/(syslog|kern\.log|auth\.log|daemon\.log|messages).*"""
        )
        WizardOs.MACOS -> listOf(
            """.*\.log$""",
            """.*/(system|install|wifi|kernel).*\.log$"""
        )
        WizardOs.WINDOWS -> listOf(
            """(?i).*\.log$""",
            """(?i).*\.etl$""",
            """(?i).*\.txt$"""
        )
    }

    private fun manInfoIndexFilePatterns(os: WizardOs): List<String> = when (os) {
        WizardOs.WINDOWS -> listOf("""(?i).*[\\/](man|help|docs?)[\\/].*\.(txt|md|html?)$""")
        WizardOs.LINUX, WizardOs.MACOS -> listOf(
            """.*/man[1-9]/.*""",
            """.*/share/(man|info|doc)/.*\.(txt|md|html?)$"""
        )
    }

    private data class WizardPresetConfig(
        val name: String,
        val label: String,
        val description: String,
        val startPaths: List<String>,
        val maxDepth: Int,
        val enabledByDefault: Boolean = true,
        val parallelRecommended: Boolean = true,
        val forceFollowLinks: Boolean = false,
        val folderSkipPatterns: List<String> = emptyList(),
        val folderLocatePatterns: List<String> = emptyList(),
        val folderIndexPatterns: List<String> = emptyList(),
        val folderAnalyzePatterns: List<String> = emptyList(),
        val fileSkipPatterns: List<String> = emptyList(),
        val fileLocatePatterns: List<String> = emptyList(),
        val fileIndexPatterns: List<String> = emptyList(),
        val fileAnalyzePatterns: List<String> = emptyList()
    )

    private data class WizardOsOption(
        val value: String,
        val label: String
    )

    private enum class WizardOs(val displayName: String, val prefix: String) {
        LINUX("Linux", "LINUX"),
        WINDOWS("Windows 11", "WIN11"),
        MACOS("macOS", "MAC")
    }

}
