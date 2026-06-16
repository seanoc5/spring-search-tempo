package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FolderSnapshot
import com.oconeco.spring_search_tempo.base.domain.HiddenGemResolution
import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.base.repos.FolderSnapshotRepository
import com.oconeco.spring_search_tempo.base.repos.HiddenGemResolutionRepository
import com.oconeco.spring_search_tempo.batch.audit.FolderAuditReconciliationService
import com.oconeco.spring_search_tempo.batch.audit.FolderAuditService
import com.oconeco.spring_search_tempo.batch.audit.HiddenGemResolutionService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.nio.file.Paths

/**
 * Admin view for the folder audit (issues #103 + #104).
 *
 *   GET  /admin/folder-audit                            — table of past runs + run-now button
 *   POST /admin/folder-audit/run                        — kicks off; redirects back with flash msg
 *   GET  /admin/folder-audit/runs/{id}                  — single-run drilldown
 *   GET  /admin/folder-audit/{runId}/browse?path=…      — browseable snapshot view (#104)
 *   GET  /admin/folder-audit/{runId}/hidden-gems        — hidden-gem candidate list (#104)
 *   POST /admin/folder-audit/{runId}/hidden-gems/dismiss    — durable DISMISSED (#104)
 *   POST /admin/folder-audit/{runId}/hidden-gems/reclassify — durable PROMOTED_TO_* (#104)
 */
@Controller
@RequestMapping("/admin/folder-audit")
class FolderAuditAdminController(
    private val folderAuditRunRepository: FolderAuditRunRepository,
    private val folderSnapshotRepository: FolderSnapshotRepository,
    private val hiddenGemResolutionRepository: HiddenGemResolutionRepository,
    private val folderAuditService: FolderAuditService,
    private val hiddenGemResolutionService: HiddenGemResolutionService,
    private val reconciliationService: FolderAuditReconciliationService
) {

    companion object {
        private val log = LoggerFactory.getLogger(FolderAuditAdminController::class.java)
    }

    @GetMapping
    fun list(
        @RequestParam(name = "limit", required = false, defaultValue = "50") rawLimit: Int,
        model: Model
    ): String {
        val limit = rawLimit.coerceIn(1, 500)
        val runs = folderAuditRunRepository.findAllByOrderByStartedDesc(PageRequest.of(0, limit))
        model.addAttribute("runs", runs)
        model.addAttribute("limit", limit)
        return "admin/folder-audit/list"
    }

    @PostMapping("/run")
    fun startRun(redirectAttributes: RedirectAttributes): String {
        log.info("Admin UI request to start folder audit run")
        return try {
            val runId = folderAuditService.startFilesystemAuditRun()
            redirectAttributes.addFlashAttribute(
                "successMessage",
                "Folder audit run #$runId started."
            )
            "redirect:/admin/folder-audit"
        } catch (e: Exception) {
            log.error("Failed to start folder audit run from admin UI", e)
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                "Failed to start folder audit: ${e.message}"
            )
            "redirect:/admin/folder-audit"
        }
    }

    @GetMapping("/runs/{id}")
    fun runDetail(
        @PathVariable id: Long,
        @RequestParam(name = "groundTruthCount", required = false) groundTruthCount: Long?,
        @RequestParam(name = "sourceCommand", required = false) sourceCommand: String?,
        model: Model
    ): String {
        val run = folderAuditRunRepository.findById(id).orElse(null)
            ?: return "redirect:/admin/folder-audit"
        model.addAttribute("run", run)
        model.addAttribute("pathBreakdown", reconciliationService.computeBreakdown(id))
        // The reconciliation form GETs back to this same URL; when the
        // operator supplies a non-negative ground-truth count we render the
        // delta block. Negative values fall through silently — the HTML
        // form's `min=0` handles that on the client and the controller stays
        // permissive for direct URL hits.
        if (groundTruthCount != null && groundTruthCount >= 0) {
            model.addAttribute(
                "reconciliation",
                reconciliationService.reconcile(id, groundTruthCount, sourceCommand)
            )
        }
        return "admin/folder-audit/detail"
    }

    @GetMapping("/{runId}/browse")
    fun browse(
        @PathVariable runId: Long,
        @RequestParam(name = "path", required = false) rawPath: String?,
        model: Model
    ): String {
        val run = folderAuditRunRepository.findById(runId).orElse(null)
            ?: return "redirect:/admin/folder-audit"

        val rootSnapshot = pickRootSnapshot(runId, rawPath)
        if (rootSnapshot == null) {
            model.addAttribute("run", run)
            model.addAttribute("currentPath", rawPath ?: "")
            model.addAttribute("children", emptyList<FolderSnapshot>())
            model.addAttribute("breadcrumbs", emptyList<Breadcrumb>())
            model.addAttribute("resolutionsByPath", emptyMap<String, HiddenGemResolution>())
            model.addAttribute("noRoot", true)
            return "admin/folder-audit/browse"
        }

        val currentPath = rootSnapshot.path!!
        val children = folderSnapshotRepository
            .findByAuditRunIdAndParentPathOrderByPathAsc(runId, currentPath)

        val sourceRef = run.sourceRef ?: "(unknown)"
        val resolutionsByPath = (children.mapNotNull { it.path } + currentPath)
            .mapNotNull { p -> hiddenGemResolutionRepository.findBySourceRefAndPath(sourceRef, p)?.let { p to it } }
            .toMap()

        model.addAttribute("run", run)
        model.addAttribute("currentPath", currentPath)
        model.addAttribute("currentSnapshot", rootSnapshot)
        model.addAttribute("children", children)
        model.addAttribute("breadcrumbs", buildBreadcrumbs(runId, currentPath))
        model.addAttribute("resolutionsByPath", resolutionsByPath)
        return "admin/folder-audit/browse"
    }

    @GetMapping("/{runId}/hidden-gems")
    fun hiddenGems(@PathVariable runId: Long, model: Model): String {
        val run = folderAuditRunRepository.findById(runId).orElse(null)
            ?: return "redirect:/admin/folder-audit"
        val sourceRef = run.sourceRef ?: "(unknown)"
        val candidates = folderSnapshotRepository.findUnresolvedHiddenGems(runId, sourceRef)
        model.addAttribute("run", run)
        model.addAttribute("candidates", candidates)
        return "admin/folder-audit/hidden-gems"
    }

    @PostMapping("/{runId}/hidden-gems/dismiss")
    fun dismissHiddenGem(
        @PathVariable runId: Long,
        @RequestParam("path") path: String,
        @AuthenticationPrincipal principal: UserDetails?,
        redirectAttributes: RedirectAttributes
    ): String {
        return try {
            hiddenGemResolutionService.dismiss(runId, path, principal?.username)
            redirectAttributes.addFlashAttribute(
                "successMessage",
                "Dismissed hidden-gem candidate: $path"
            )
            "redirect:/admin/folder-audit/$runId/hidden-gems"
        } catch (e: Exception) {
            log.error("Failed to dismiss hidden-gem runId={} path={}", runId, path, e)
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                "Failed to dismiss: ${e.message}"
            )
            "redirect:/admin/folder-audit/$runId/hidden-gems"
        }
    }

    @PostMapping("/{runId}/hidden-gems/reclassify")
    fun reclassifyHiddenGem(
        @PathVariable runId: Long,
        @RequestParam("path") path: String,
        @RequestParam("target") target: String,
        @AuthenticationPrincipal principal: UserDetails?,
        redirectAttributes: RedirectAttributes
    ): String {
        val analysisStatus = try {
            AnalysisStatus.valueOf(target.uppercase())
        } catch (e: IllegalArgumentException) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                "Unsupported reclassify target: $target"
            )
            return "redirect:/admin/folder-audit/$runId/hidden-gems"
        }
        return try {
            hiddenGemResolutionService.reclassify(runId, path, analysisStatus, principal?.username)
            redirectAttributes.addFlashAttribute(
                "successMessage",
                "Reclassified $path to $analysisStatus"
            )
            "redirect:/admin/folder-audit/$runId/hidden-gems"
        } catch (e: Exception) {
            log.error(
                "Failed to reclassify hidden-gem runId={} path={} target={}",
                runId, path, target, e
            )
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                "Failed to reclassify: ${e.message}"
            )
            "redirect:/admin/folder-audit/$runId/hidden-gems"
        }
    }

    /**
     * Pick the snapshot row to anchor the browse view on. When the
     * caller passes `?path=…`, use that row directly. Otherwise pick
     * the shallowest snapshot for the run as the root — that's the
     * crawl's startPath (depth=0 in the visitor).
     */
    private fun pickRootSnapshot(runId: Long, rawPath: String?): FolderSnapshot? {
        if (!rawPath.isNullOrBlank()) {
            return folderSnapshotRepository.findByAuditRunIdAndPath(runId, rawPath)
        }
        return folderSnapshotRepository.findFirstByAuditRunIdOrderByDepthAsc(runId)
    }

    private fun buildBreadcrumbs(runId: Long, currentPath: String): List<Breadcrumb> {
        // Walk parent links from the snapshot table so the chain stops
        // at the audited start path (no extra crumbs for the host's
        // /home/foo prefix above it).
        val crumbs = mutableListOf<Breadcrumb>()
        var cursor: FolderSnapshot? = folderSnapshotRepository
            .findByAuditRunIdAndPath(runId, currentPath)
        while (cursor != null) {
            val path = cursor.path ?: break
            val name = Paths.get(path).fileName?.toString() ?: path
            crumbs.add(Breadcrumb(name = name, path = path))
            val parent = cursor.parentPath ?: break
            cursor = folderSnapshotRepository.findByAuditRunIdAndPath(runId, parent)
        }
        return crumbs.reversed()
    }

    data class Breadcrumb(val name: String, val path: String)
}
