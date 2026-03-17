package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.config.HostNameHolder
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.service.SourceHostService
import com.oconeco.spring_search_tempo.base.service.SmartDeleteService
import com.oconeco.spring_search_tempo.web.service.DiscoveryService
import com.oconeco.spring_search_tempo.web.service.DiscoverySessionSummaryDTO
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/hosts")
class HostController(
    private val crawlConfigService: DatabaseCrawlConfigService,
    private val discoveryService: DiscoveryService,
    private val fileService: FSFileService,
    private val folderService: FSFolderService,
    private val smartDeleteService: SmartDeleteService,
    private val sourceHostService: SourceHostService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun index(
        @RequestParam(name = "host", required = false) selectedHost: String?,
        @RequestParam(name = "tab", required = false, defaultValue = "configs") tab: String,
        model: Model
    ): String {
        log.debug(".... Retrieving host UI for host: $selectedHost, tab: $tab")
        val knownHosts = crawlConfigService.findDistinctSourceHosts()
        val currentHost = HostNameHolder.currentHostName

        // Default to current server's host if none selected
        val effectiveHost = selectedHost?.takeIf { it.isNotBlank() && knownHosts.contains(it) }
            ?: knownHosts.find { it.equals(currentHost, ignoreCase = true) }
            ?: knownHosts.firstOrNull()
            ?: currentHost

        model.addAttribute("knownHosts", knownHosts)
        model.addAttribute("currentHost", currentHost)
        model.addAttribute("selectedHost", effectiveHost)
        model.addAttribute("activeTab", tab)

        // Load data for the selected host
        if (effectiveHost.isNotBlank()) {
            when (tab) {
                "configs" -> loadConfigsTab(effectiveHost, model)
                "discovery" -> loadDiscoveryTab(effectiveHost, model)
                "content" -> loadContentTab(effectiveHost, model)
            }
        }

        return "host/index"
    }

    @GetMapping("/{host}/configs")
    fun configsFragment(
        @PathVariable(name = "host") host: String,
        model: Model
    ): String {
        log.debug(".... Retrieving host configs for host: $host")
        loadConfigsTab(host, model)
        model.addAttribute("selectedHost", host)
        return "host/fragments :: configsTab"
    }

    @GetMapping("/{host}/discovery")
    fun discoveryFragment(
        @PathVariable(name = "host") host: String,
        model: Model
    ): String {
        log.debug(".... Retrieving host discovery sessions for host: $host")
        loadDiscoveryTab(host, model)
        model.addAttribute("selectedHost", host)
        return "host/fragments :: discoveryTab"
    }

    @GetMapping("/{host}/content")
    fun contentFragment(
        @PathVariable(name = "host") host: String,
        model: Model
    ): String {
        log.debug(".... Retrieving host content stats for host: $host")
        loadContentTab(host, model)
        model.addAttribute("selectedHost", host)
        return "host/fragments :: contentTab"
    }

    @PostMapping("/{host}/purge")
    fun purgeHost(
        @PathVariable(name = "host") host: String,
        redirectAttributes: RedirectAttributes
    ): String {
        log.warn("Purging host: $host")
        return try {
            val summary = smartDeleteService.purgeSourceHost(host)
            redirectAttributes.addFlashAttribute(
                "message",
                "Purged source host '${summary.sourceHost}': deleted ${summary.crawlConfigsDeleted} crawl configs, ${summary.discoverySessionsDeleted} discovery sessions, ${summary.emailAccountsDeleted} email accounts, ${summary.emailMessagesDeleted} email messages, ${summary.emailFoldersDeleted} email folders, ${summary.filesDeleted} files, ${summary.foldersDeleted} folders, ${summary.chunksDeleted} chunks, and ${summary.jobRunsDeleted} job runs."
            )
            "redirect:/hosts?host=$host&tab=configs"
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error", "Purge failed for '$host': ${e.message}")
            "redirect:/hosts?host=$host&tab=configs"
        }
    }

    @PostMapping("/sync-source-hosts")
    fun syncSourceHosts(redirectAttributes: RedirectAttributes): String {
        return try {
            val result = sourceHostService.backfillCoreReferences()
            redirectAttributes.addFlashAttribute(
                "message",
                "Synced core SourceHost refs: hosts=${result.hostsCreatedOrResolved}, configs=${result.crawlConfigsUpdated}, " +
                    "discovery=${result.discoverySessionsUpdated}, ownership=${result.userAssignmentsUpdated}, " +
                    "tasks=${result.remoteTasksUpdated}, runs=${result.discoveryRunsUpdated}, obs=${result.discoveryObservationsUpdated}. " +
                    "Filesystem rows are backfilled via docs/sql/014-source-host.sql."
            )
            "redirect:/hosts"
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error", "SourceHost sync failed: ${e.message}")
            "redirect:/hosts"
        }
    }

    private fun loadConfigsTab(host: String, model: Model) {
        log.debug(".... Retrieving host configs for host: $host")
        // Get all configs for this host
        val allConfigs = crawlConfigService.findAll(null, Pageable.unpaged())
        val hostConfigs = allConfigs.content.filter {
            it.sourceHost?.equals(host, ignoreCase = true) == true
        }

        // Build stats for each config
        val configStats = hostConfigs.associate { config ->
            val fileCount = fileService.countByCrawlConfigId(config.id!!, includeSkipped = false)
            val folderCount = folderService.countByCrawlConfigId(config.id!!, includeSkipped = false)
            config.id!! to HostConfigStats(fileCount, folderCount)
        }

        model.addAttribute("configs", hostConfigs)
        model.addAttribute("configStats", configStats)
    }

    private fun loadDiscoveryTab(host: String, model: Model) {
        log.debug(".... Retrieving host discovery sessions for host: $host")
        val sessions = discoveryService.getSessionsForHost(host)
        model.addAttribute("discoverySessions", sessions)
    }

    private fun loadContentTab(host: String, model: Model) {
        log.debug(".... Retrieving host content stats for host: $host")
        // Get aggregate stats for all configs on this host
        val allConfigs = crawlConfigService.findAll(null, Pageable.unpaged())
        val hostConfigIds = allConfigs.content
            .filter { it.sourceHost?.equals(host, ignoreCase = true) == true }
            .mapNotNull { it.id }

        var totalFiles = 0L
        var totalFolders = 0L
        var totalSearchable = 0L

        val searchableCounts = fileService.countSearchableByCrawlConfig()

        hostConfigIds.forEach { configId ->
            totalFiles += fileService.countByCrawlConfigId(configId, includeSkipped = false)
            totalFolders += folderService.countByCrawlConfigId(configId, includeSkipped = false)
            totalSearchable += searchableCounts[configId] ?: 0L
        }

        model.addAttribute("totalFiles", totalFiles)
        model.addAttribute("totalFolders", totalFolders)
        model.addAttribute("totalSearchable", totalSearchable)
        model.addAttribute("configCount", hostConfigIds.size)
    }
}

data class HostConfigStats(
    val fileCount: Long,
    val folderCount: Long
)
