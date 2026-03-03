package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.oconeco.spring_search_tempo.web.service.ClassifyFolderRequest
import com.oconeco.spring_search_tempo.web.service.DiscoveryService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
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
            val session = discoveryService.getSessionForClassification(sessionId)

            // Build tree structure for display (limit depth for performance)
            val rootFolders = session.folders.filter { it.depth == 0 }
            val foldersByParent = session.folders.groupBy { it.parentPath }

            model.addAttribute("session", session)
            model.addAttribute("rootFolders", rootFolders)
            model.addAttribute("foldersByParent", foldersByParent)
            model.addAttribute("maxDepth", maxDepth)
            model.addAttribute("analysisStatuses", AnalysisStatus.entries)

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
}
