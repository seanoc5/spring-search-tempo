package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.ConceptHierarchyService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/conceptHierarchies")
class ConceptHierarchyController(
    private val conceptHierarchyService: ConceptHierarchyService
) {

    companion object {
        private val log = LoggerFactory.getLogger(ConceptHierarchyController::class.java)
        private const val DEFAULT_HIERARCHY = "OCONECO"
    }

    @GetMapping
    fun index(): String = "redirect:/conceptHierarchies/$DEFAULT_HIERARCHY"

    @GetMapping("/{code}")
    fun hierarchyPage(
        @PathVariable code: String,
        @RequestParam(name = "nodeId", required = false) nodeId: Long?,
        @RequestParam(name = "q", required = false) query: String?,
        @RequestParam(name = "scope", required = false, defaultValue = "current") scope: String,
        model: Model
    ): String {
        val hierarchyCode = code.uppercase()
        val globalSearch = scope.equals("all", ignoreCase = true)
        model.addAttribute("hierarchies", conceptHierarchyService.listHierarchies())
        model.addAttribute("hierarchy", conceptHierarchyService.getHierarchySummary(hierarchyCode))
        model.addAttribute("selectedHierarchyCode", hierarchyCode)
        model.addAttribute("selectedNodeId", nodeId)
        model.addAttribute("query", query ?: "")
        model.addAttribute("searchScope", if (globalSearch) "all" else "current")

        if (nodeId != null) {
            val nodeDetail = conceptHierarchyService.getNodeDetail(nodeId)
            model.addAttribute("nodeDetail", nodeDetail)
            if (nodeDetail.node.hierarchyCode != hierarchyCode) {
                return "redirect:/conceptHierarchies/${nodeDetail.node.hierarchyCode}?nodeId=${nodeId}"
            }
        } else {
            model.addAttribute("rootNodes", conceptHierarchyService.getRootNodes(hierarchyCode))
        }

        if (!query.isNullOrBlank()) {
            model.addAttribute(
                "searchResults",
                conceptHierarchyService.search(query, if (globalSearch) null else hierarchyCode, limit = 100)
            )
        }

        return "conceptHierarchy/workspace"
    }

    @PostMapping("/oconeco/import")
    fun importOconeco(
        @RequestParam("file") file: MultipartFile,
        redirectAttributes: RedirectAttributes
    ): String {
        if (file.isEmpty) {
            redirectAttributes.addFlashAttribute("MSG_ERROR", "Please upload an XLSX workbook.")
            return "redirect:/conceptHierarchies/oconeco"
        }

        try {
            file.inputStream.use { input ->
                val result = conceptHierarchyService.importOconecoHierarchy(input, file.originalFilename)
                redirectAttributes.addFlashAttribute(
                    "MSG_SUCCESS",
                    "Imported ${result.importedRows} OconEco rows: " +
                        "${result.createdNodes} created, ${result.updatedNodes} updated, " +
                        "${result.deactivatedNodes} deactivated."
                )
                redirectAttributes.addFlashAttribute("importResult", result)
            }
        } catch (e: Exception) {
            log.error("Failed to import OconEco hierarchy from {}", file.originalFilename, e)
            redirectAttributes.addFlashAttribute("MSG_ERROR", "OconEco import failed: ${e.message}")
        }

        return "redirect:/conceptHierarchies/oconeco"
    }
}
