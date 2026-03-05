package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.oconeco.spring_search_tempo.web.service.ApplyDiscoveryMode
import com.oconeco.spring_search_tempo.web.service.ApplyDiscoveryRequest
import com.oconeco.spring_search_tempo.web.service.ClassifyFolderRequest
import com.oconeco.spring_search_tempo.web.service.DiscoveryService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.bind.annotation.*

/**
 * Controller for discovery session classification UI.
 *
 * Provides pages for:
 * - Listing pending discovery sessions
 * - Classifying folders in a discovery session
 * - Applying classifications
 */
@Controller
@RequestMapping("/discovery")
class DiscoveryController(
    private val discoveryService: DiscoveryService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * List all discovery sessions.
     */
    @GetMapping
    fun list(model: Model): String {
        val pendingSessions = discoveryService.getPendingSessions()
        model.addAttribute("sessions", pendingSessions)
        return "discovery/list"
    }

    /**
     * Show classification UI for a discovery session.
     */
    @GetMapping("/{sessionId}/classify")
    fun classify(
        @PathVariable sessionId: Long,
        @RequestParam(name = "maxDepth", defaultValue = "3") maxDepth: Int,
        model: Model
    ): String {
        try {
            val discoverySession = discoveryService.getSessionForClassification(sessionId)

            // Build tree structure for display (limit depth for performance)
            val rootFolders = discoverySession.folders.filter { it.depth == 0 }
            val foldersByParent = discoverySession.folders.groupBy { it.parentPath }

            model.addAttribute("discoverySession", discoverySession)
            model.addAttribute("rootFolders", rootFolders)
            model.addAttribute("foldersByParent", foldersByParent)
            model.addAttribute("maxDepth", maxDepth)
            model.addAttribute("analysisStatuses", AnalysisStatus.entries)
            model.addAttribute("hostCrawlConfigs", discoveryService.getCrawlConfigCandidates(discoverySession.host))
            model.addAttribute("defaultNewConfigName", "DISCOVERY_${discoverySession.host}_${discoverySession.id}")
            model.addAttribute("defaultNewDisplayLabel", "Discovery ${discoverySession.host} (${discoverySession.id})")

            return "discovery/classify"
        } catch (e: NotFoundException) {
            model.addAttribute("error", "Discovery session not found")
            return "discovery/list"
        }
    }

    /**
     * Get child folders for lazy loading (HTMX).
     */
    @GetMapping("/{sessionId}/children")
    fun getChildren(
        @PathVariable sessionId: Long,
        @RequestParam parentPath: String,
        model: Model
    ): String {
        val children = discoveryService.getChildFolders(sessionId, parentPath)
        model.addAttribute("folders", children)
        model.addAttribute("sessionId", sessionId)
        return "discovery/fragments :: folderChildren"
    }

    /**
     * Classify a folder (HTMX).
     */
    @PostMapping("/{sessionId}/classify")
    @ResponseBody
    fun classifyFolder(
        @PathVariable sessionId: Long,
        @RequestBody request: ClassifyFolderRequest
    ): ResponseEntity<Map<String, Any>> {
        return try {
            val status = AnalysisStatus.valueOf(request.status.uppercase())

            val updated = if (request.includeSubtree && request.folderPath != null) {
                discoveryService.classifySubtree(sessionId, request.folderPath, status)
            } else if (request.folderId != null) {
                discoveryService.classifyFolder(sessionId, request.folderId, status)
            } else {
                0
            }

            val sessionStatus = discoveryService.getStatus(sessionId)

            ResponseEntity.ok(mapOf(
                "success" to true,
                "updated" to updated,
                "classifiedFolders" to sessionStatus.classifiedFolders,
                "skipCount" to sessionStatus.skipCount,
                "locateCount" to sessionStatus.locateCount,
                "indexCount" to sessionStatus.indexCount
            ))
        } catch (e: Exception) {
            log.error("Failed to classify folder", e)
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "error" to (e.message ?: "Classification failed")
            ))
        }
    }

    /**
     * Apply all suggested statuses.
     */
    @PostMapping("/{sessionId}/apply-suggestions")
    @ResponseBody
    fun applySuggestions(@PathVariable sessionId: Long): ResponseEntity<Map<String, Any>> {
        return try {
            val result = discoveryService.applySuggestedStatuses(sessionId)
            ResponseEntity.ok(mapOf(
                "success" to true,
                "totalApplied" to result.totalApplied,
                "skipApplied" to result.skipApplied,
                "locateApplied" to result.locateApplied,
                "indexApplied" to result.indexApplied
            ))
        } catch (e: Exception) {
            log.error("Failed to apply suggestions", e)
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "error" to (e.message ?: "Failed to apply suggestions")
            ))
        }
    }

    /**
     * Get session status (HTMX polling).
     */
    @GetMapping("/{sessionId}/status")
    fun getStatus(@PathVariable sessionId: Long, model: Model): String {
        val status = discoveryService.getStatus(sessionId)
        model.addAttribute("status", status)
        return "discovery/fragments :: statusPanel"
    }

    /**
     * Apply classified discovery folder statuses to a crawl config.
     */
    @PostMapping("/{sessionId}/apply-crawl-config")
    fun applyToCrawlConfig(
        @PathVariable sessionId: Long,
        @RequestParam(name = "applyMode", defaultValue = "CREATE") applyMode: String,
        @RequestParam(name = "crawlConfigId", required = false) crawlConfigId: Long?,
        @RequestParam(name = "newConfigName", required = false) newConfigName: String?,
        @RequestParam(name = "newDisplayLabel", required = false) newDisplayLabel: String?,
        @RequestParam(name = "enableConfig", required = false, defaultValue = "false") enableConfig: Boolean,
        redirectAttributes: RedirectAttributes
    ): String {
        return try {
            val mode = runCatching { ApplyDiscoveryMode.valueOf(applyMode.uppercase()) }
                .getOrDefault(ApplyDiscoveryMode.CREATE)

            val result = discoveryService.applyToCrawlConfig(
                sessionId = sessionId,
                request = ApplyDiscoveryRequest(
                    mode = mode,
                    crawlConfigId = crawlConfigId,
                    newConfigName = newConfigName,
                    newDisplayLabel = newDisplayLabel,
                    enableConfig = enableConfig
                )
            )

            redirectAttributes.addFlashAttribute(
                "message",
                "${result.action.name.lowercase().replaceFirstChar { it.uppercase() }} crawl config ${result.crawlConfigId} " +
                    "(patterns: skip=${result.skipPatterns}, locate=${result.locatePatterns}, " +
                    "index=${result.indexPatterns}, analyze=${result.analyzePatterns})"
            )
            "redirect:/crawlConfigs/${result.crawlConfigId}/edit"
        } catch (e: Exception) {
            log.error("Failed to apply discovery session {} to crawl config", sessionId, e)
            redirectAttributes.addFlashAttribute("error", "Apply failed: ${e.message}")
            "redirect:/discovery/$sessionId/classify"
        }
    }
}
