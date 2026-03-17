package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.DiscoveryRuleGroup
import com.oconeco.spring_search_tempo.base.domain.DiscoveryRuleOperation
import com.oconeco.spring_search_tempo.base.service.SmartDeleteService
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.oconeco.spring_search_tempo.web.service.ApplyDiscoveryMode
import com.oconeco.spring_search_tempo.web.service.ApplyDiscoveryRequest
import com.oconeco.spring_search_tempo.web.service.ClassifyFolderRequest
import com.oconeco.spring_search_tempo.web.service.DiscoveryRuleAdminService
import com.oconeco.spring_search_tempo.web.service.DiscoveryRuleUpsertRequest
import com.oconeco.spring_search_tempo.web.service.DiscoveryUserProfile
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
    private val discoveryService: DiscoveryService,
    private val discoveryRuleAdminService: DiscoveryRuleAdminService,
    private val smartDeleteService: SmartDeleteService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * List discovery sessions.
     */
    @GetMapping
    fun list(model: Model): String {
        val sessions = discoveryService.getAllSessions()
        log.debug(".... Retrieved {} (remote crawl) discovery sessions", sessions.size)
        model.addAttribute("sessions", sessions)
        return "discovery/list"
    }

    /**
     * Manage dynamic classification rules used by onboarding templates.
     */
    @GetMapping("/rules")
    fun rules(
        @RequestParam(name = "previewSessionId", required = false) previewSessionId: Long?,
        @RequestParam(name = "previewProfile", required = false) previewProfile: String?,
        model: Model
    ): String {
        log.debug(".... Loading discovery rules and options for UI")

        model.addAttribute("rules", discoveryRuleAdminService.listRules())
        model.addAttribute("ruleGroups", DiscoveryRuleGroup.entries)
        model.addAttribute("ruleOperations", DiscoveryRuleOperation.entries)
        model.addAttribute("profileOptions", DiscoveryUserProfile.entries)
        model.addAttribute("sessionOptions", discoveryRuleAdminService.listSessionOptions())
        model.addAttribute("selectedPreviewSessionId", previewSessionId)
        model.addAttribute("selectedPreviewProfile", previewProfile ?: "")

        if (previewSessionId != null) {
            runCatching {
                val forcedProfile = previewProfile
                    ?.takeIf { it.isNotBlank() }
                    ?.let { DiscoveryUserProfile.valueOf(it.uppercase()) }
                discoveryRuleAdminService.previewImpact(previewSessionId, forcedProfile)
            }.onSuccess {
                model.addAttribute("preview", it)
            }.onFailure { ex ->
                model.addAttribute("error", "Preview failed: ${ex.message}")
            }
        }

        return "discovery/rules"
    }

    @PostMapping("/rules")
    fun createRule(
        @RequestParam(name = "ruleGroup") ruleGroup: String,
        @RequestParam(name = "operation") operation: String,
        @RequestParam(name = "matchValue") matchValue: String,
        @RequestParam(name = "enabled", required = false, defaultValue = "false") enabled: Boolean,
        @RequestParam(name = "note", required = false) note: String?,
        redirectAttributes: RedirectAttributes
    ): String {
        log.debug(".... Creating discovery rule")
        return try {
            discoveryRuleAdminService.createRule(
                DiscoveryRuleUpsertRequest(
                    ruleGroup = ruleGroup,
                    operation = operation,
                    matchValue = matchValue,
                    enabled = enabled,
                    note = note
                )
            )
            redirectAttributes.addFlashAttribute("message", "Rule created.")
            "redirect:/discovery/rules"
        } catch (e: Exception) {
            log.error("Failed to create discovery rule", e)
            redirectAttributes.addFlashAttribute("error", "Create failed: ${e.message}")
            "redirect:/discovery/rules"
        }
    }

    @PostMapping("/rules/{ruleId}")
    fun updateRule(
        @PathVariable ruleId: Long,
        @RequestParam(name = "ruleGroup") ruleGroup: String,
        @RequestParam(name = "operation") operation: String,
        @RequestParam(name = "matchValue") matchValue: String,
        @RequestParam(name = "enabled", required = false, defaultValue = "false") enabled: Boolean,
        @RequestParam(name = "note", required = false) note: String?,
        redirectAttributes: RedirectAttributes
    ): String {
        log.debug(".... Updating discovery rule {}", ruleId)
        return try {
            discoveryRuleAdminService.updateRule(
                ruleId = ruleId,
                request = DiscoveryRuleUpsertRequest(
                    ruleGroup = ruleGroup,
                    operation = operation,
                    matchValue = matchValue,
                    enabled = enabled,
                    note = note
                )
            )
            redirectAttributes.addFlashAttribute("message", "Rule $ruleId updated.")
            "redirect:/discovery/rules"
        } catch (e: Exception) {
            log.error("Failed to update discovery rule {}", ruleId, e)
            redirectAttributes.addFlashAttribute("error", "Update failed: ${e.message}")
            "redirect:/discovery/rules"
        }
    }

    @PostMapping("/rules/{ruleId}/delete")
    fun deleteRule(
        @PathVariable ruleId: Long,
        redirectAttributes: RedirectAttributes
    ): String {
        log.debug(".... Deleting discovery rule {}", ruleId)
        return try {
            discoveryRuleAdminService.deleteRule(ruleId)
            redirectAttributes.addFlashAttribute("message", "Rule $ruleId deleted.")
            "redirect:/discovery/rules"
        } catch (e: Exception) {
            log.error("Failed to delete discovery rule {}", ruleId, e)
            redirectAttributes.addFlashAttribute("error", "Delete failed: ${e.message}")
            "redirect:/discovery/rules"
        }
    }

    /**
     * Show classification UI for a discovery session.
     */
    @GetMapping("/{sessionId}/classify")
    fun classify(
        @PathVariable sessionId: Long,
        @RequestParam(name = "maxDepth", defaultValue = "3") maxDepth: Int,
        @RequestParam(name = "assignedStatus", required = false) assignedStatus: String?,
        @RequestParam(name = "listPage", defaultValue = "0") listPage: Int,
        @RequestParam(name = "listSize", defaultValue = "100") listSize: Int,
        @RequestParam(name = "listSort", defaultValue = "path") listSort: String,
        @RequestParam(name = "listDir", defaultValue = "asc") listDir: String,
        @RequestParam(name = "listQuery", required = false) listQuery: String?,
        model: Model
    ): String {
        try {
            log.debug(".... Retrieving classification UI for session {} with maxDepth {}", sessionId, maxDepth)
            val effectiveMaxDepth = maxDepth.coerceIn(0, 8)
            val discoverySession = discoveryService.getSessionForClassification(sessionId, effectiveMaxDepth)
            val initialTree = discoveryService.getInitialFolderTree(sessionId, effectiveMaxDepth)
            val selectedAssignedStatus = parseAnalysisStatus(assignedStatus)
            val assignedFolders = selectedAssignedStatus?.let {
                discoveryService.getAssignedFolders(
                    sessionId = sessionId,
                    status = it,
                    page = listPage,
                    size = listSize,
                    sortBy = listSort,
                    sortDir = listDir,
                    pathFilter = listQuery
                )
            }
            val assignedPageNumber = assignedFolders?.pageNumber ?: 0
            val assignedPageSize = assignedFolders?.pageSize ?: listSize.coerceIn(10, 500)
            val assignedTotal = assignedFolders?.totalCount ?: 0L
            val assignedFrom = if (assignedTotal == 0L) 0L else (assignedPageNumber.toLong() * assignedPageSize) + 1L
            val assignedTo = if (assignedTotal == 0L) {
                0L
            } else {
                minOf(assignedTotal, (assignedPageNumber.toLong() + 1L) * assignedPageSize)
            }

            // Build initial tree structure for display. Remaining branches load on demand.
            val rootFolders = initialTree.folders.filter { it.depth == 0 }
            val foldersByParent = initialTree.folders
                // Defensive guard for malformed rows where parentPath == path (self-cycle).
                .filterNot { it.parentPath != null && it.parentPath == it.path }
                .groupBy { it.parentPath }

            model.addAttribute("discoverySession", discoverySession)
            model.addAttribute("sessionId", discoverySession.id)
            model.addAttribute("rootFolders", rootFolders)
            model.addAttribute("foldersByParent", foldersByParent)
            model.addAttribute("fullyLoadedParentPaths", initialTree.fullyLoadedParentPaths)
            model.addAttribute("maxDepth", effectiveMaxDepth)
            model.addAttribute("visibleFolders", initialTree.folders.size)
            model.addAttribute("selectedAssignedStatus", selectedAssignedStatus?.name)
            model.addAttribute("assignedFolders", assignedFolders?.folders.orEmpty())
            model.addAttribute("assignedFoldersTotal", assignedTotal)
            model.addAttribute("assignedFoldersFrom", assignedFrom)
            model.addAttribute("assignedFoldersTo", assignedTo)
            model.addAttribute("assignedFoldersPage", assignedPageNumber)
            model.addAttribute("assignedFoldersPageSize", assignedPageSize)
            model.addAttribute("assignedFoldersTotalPages", assignedFolders?.totalPages ?: 0)
            model.addAttribute("assignedFoldersHasPrevious", assignedFolders?.hasPrevious ?: false)
            model.addAttribute("assignedFoldersHasNext", assignedFolders?.hasNext ?: false)
            model.addAttribute("assignedFoldersSort", assignedFolders?.sortBy ?: listSort)
            model.addAttribute("assignedFoldersDir", assignedFolders?.sortDir ?: listDir)
            model.addAttribute("assignedFoldersQuery", assignedFolders?.filter ?: (listQuery?.trim() ?: ""))
            model.addAttribute("listSize", assignedFolders?.pageSize ?: listSize.coerceIn(10, 500))
            model.addAttribute("analysisStatuses", AnalysisStatus.entries)
            model.addAttribute("profileOptions", DiscoveryUserProfile.entries)
            model.addAttribute("hostCrawlConfigs", discoveryService.getCrawlConfigCandidates(discoverySession.host))
            model.addAttribute("defaultNewConfigName", "DISCOVERY_${discoverySession.host}_${discoverySession.id}")
            model.addAttribute("defaultNewDisplayLabel", "Discovery ${discoverySession.host} (${discoverySession.id})")
            log.debug("\t\trender template: classify.html...")

            return "discovery/classify"
        } catch (e: NotFoundException) {
            model.addAttribute("error", "Discovery session not found")
            model.addAttribute("sessions", discoveryService.getAllSessions())
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
        @RequestParam depth: Int,
        @RequestParam maxDepth: Int,
        @RequestParam(name = "inheritedStatus", required = false) inheritedStatus: String?,
        model: Model
    ): String {
        val children = discoveryService.getChildFolders(sessionId, parentPath)
        log.debug(".... Retrieved {} children for session {} and parent {}", children.size, sessionId, parentPath)
        model.addAttribute("folders", children)
        model.addAttribute("sessionId", sessionId)
        model.addAttribute("depth", depth)
        model.addAttribute("maxDepth", maxDepth)
        model.addAttribute("inheritedStatus", inheritedStatus)
        return "discovery/fragments :: folderRows"
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
            log.debug(".... Classifying folder {} with status {}", request.folderPath, status)

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
                "indexCount" to sessionStatus.indexCount,
                "analyzeCount" to sessionStatus.analyzeCount,
                "semanticCount" to sessionStatus.semanticCount
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
            log.debug(".... Applied {} suggestions (session: {})", result.totalApplied, sessionId)
            ResponseEntity.ok(mapOf(
                "success" to true,
                "totalApplied" to result.totalApplied,
                "skipApplied" to result.skipApplied,
                "locateApplied" to result.locateApplied,
                "indexApplied" to result.indexApplied,
                "analyzeApplied" to result.analyzeApplied,
                "semanticApplied" to result.semanticApplied
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
    fun getStatus(
        @PathVariable sessionId: Long,
        @RequestParam(name = "maxDepth", defaultValue = "3") maxDepth: Int,
        @RequestParam(name = "assignedStatus", required = false) assignedStatus: String?,
        @RequestParam(name = "listSize", defaultValue = "100") listSize: Int,
        model: Model
    ): String {
        val status = discoveryService.getStatus(sessionId)
        log.debug(".... Session({}) status retrieved: {}", sessionId, status)

        model.addAttribute("discoverySession", status)
        model.addAttribute("sessionId", sessionId)
        model.addAttribute("maxDepth", maxDepth.coerceIn(0, 8))
        model.addAttribute("selectedAssignedStatus", parseAnalysisStatus(assignedStatus)?.name)
        model.addAttribute("listSize", listSize.coerceIn(10, 500))
        return "discovery/fragments :: statusPanel"
    }

    /**
     * Rebuild suggestion template for a selected profile.
     */
    @PostMapping("/{sessionId}/apply-template")
    fun applyTemplate(
        @PathVariable sessionId: Long,
        @RequestParam(name = "profile") profile: String,
        redirectAttributes: RedirectAttributes
    ): String {
        log.debug(".... Applying template for session {} with profile {}", sessionId, profile)
        return try {
            val selected = DiscoveryUserProfile.valueOf(profile.uppercase())
            val result = discoveryService.applySuggestedTemplate(sessionId, selected)
            redirectAttributes.addFlashAttribute(
                "message",
                "Applied ${result.profile} template suggestions: " +
                    "skip=${result.skipSuggested}, locate=${result.locateSuggested}, " +
                    "index=${result.indexSuggested}, analyze=${result.analyzeSuggested}, " +
                    "semantic=${result.semanticSuggested}."
            )
            "redirect:/discovery/$sessionId/classify"
        } catch (e: Exception) {
            log.error("Failed to apply template for session {}", sessionId, e)
            redirectAttributes.addFlashAttribute("error", "Template apply failed: ${e.message}")
            "redirect:/discovery/$sessionId/classify"
        }
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
        redirectAttributes: RedirectAttributes
    ): String {
        log.debug(".... Applying discovery session {} to crawl config", sessionId)
        return try {
            val mode = runCatching { ApplyDiscoveryMode.valueOf(applyMode.uppercase()) }
                .getOrDefault(ApplyDiscoveryMode.CREATE)

            val result = discoveryService.applyToCrawlConfig(
                sessionId = sessionId,
                request = ApplyDiscoveryRequest(
                    mode = mode,
                    crawlConfigId = crawlConfigId,
                    newConfigName = newConfigName,
                    newDisplayLabel = newDisplayLabel
                )
            )

            redirectAttributes.addFlashAttribute(
                "message",
                "${result.action.name.lowercase().replaceFirstChar { it.uppercase() }} crawl config ${result.crawlConfigId} " +
                    "(patterns: skip=${result.skipPatterns}, locate=${result.locatePatterns}, " +
                    "index=${result.indexPatterns}, analyze=${result.analyzePatterns}, semantic=${result.semanticPatterns})"
            )
            "redirect:/crawlConfigs/${result.crawlConfigId}/edit"
        } catch (e: Exception) {
            log.error("Failed to apply discovery session {} to crawl config", sessionId, e)
            redirectAttributes.addFlashAttribute("error", "Apply failed: ${e.message}")
            "redirect:/discovery/$sessionId/classify"
        }
    }

    @PostMapping("/{sessionId}/recompute-summary")
    fun recomputeSummary(
        @PathVariable sessionId: Long,
        @RequestParam(name = "redirectTo", required = false) redirectTo: String?,
        redirectAttributes: RedirectAttributes
    ): String {
        return try {
            val result = discoveryService.recomputeSessionSummary(sessionId)
            redirectAttributes.addFlashAttribute(
                "message",
                "Recomputed session ${result.sessionId}: classified=${result.classifiedFolders}, " +
                    "skip=${result.skipCount}, locate=${result.locateCount}, index=${result.indexCount}, " +
                    "analyze=${result.analyzeCount}, status=${result.status}"
            )
            safeRedirect(redirectTo, "/discovery/$sessionId/classify")
        } catch (e: Exception) {
            log.error("Failed to recompute discovery session summary {}", sessionId, e)
            redirectAttributes.addFlashAttribute("error", "Recompute failed: ${e.message}")
            safeRedirect(redirectTo, "/discovery/$sessionId/classify")
        }
    }

    @PostMapping("/{sessionId}/delete")
    fun deleteSession(
        @PathVariable sessionId: Long,
        @RequestParam(name = "redirectTo", required = false) redirectTo: String?,
        redirectAttributes: RedirectAttributes
    ): String {
        return try {
            val summary = smartDeleteService.deleteDiscoverySession(sessionId)
            redirectAttributes.addFlashAttribute(
                "message",
                "Deleted discovery session ${summary.sessionId} for '${summary.host}' with ${summary.discoveredFoldersDeleted} discovered folders."
            )
            safeRedirect(redirectTo, "/discovery")
        } catch (e: Exception) {
            log.error("Failed to delete discovery session {}", sessionId, e)
            redirectAttributes.addFlashAttribute("error", "Delete failed: ${e.message}")
            safeRedirect(redirectTo, "/discovery")
        }
    }

    private fun parseAnalysisStatus(value: String?): AnalysisStatus? {
        log.debug("Parsing analysis status: {}", value)
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { AnalysisStatus.valueOf(normalized.uppercase()) }.getOrNull()
    }

    private fun safeRedirect(redirectTo: String?, fallback: String): String {
        val target = redirectTo?.trim()?.takeIf { it.startsWith("/") } ?: fallback
        return "redirect:$target"
    }
}
