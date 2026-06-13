package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.batch.audit.FolderAuditService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Admin view for the folder audit (issue #103).
 *
 *   GET  /admin/folder-audit              — table of past runs + run-now button
 *   POST /admin/folder-audit/run          — kicks off; redirects back with flash msg
 *   GET  /admin/folder-audit/runs/{id}    — single-run drilldown
 */
@Controller
@RequestMapping("/admin/folder-audit")
class FolderAuditAdminController(
    private val folderAuditRunRepository: FolderAuditRunRepository,
    private val folderAuditService: FolderAuditService
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
    fun runDetail(@PathVariable id: Long, model: Model): String {
        val run = folderAuditRunRepository.findById(id).orElse(null)
            ?: return "redirect:/admin/folder-audit"
        model.addAttribute("run", run)
        return "admin/folder-audit/detail"
    }
}
