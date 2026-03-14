package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.ConceptHierarchyService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
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
    }

    @GetMapping("/oconeco")
    fun oconecoPage(model: Model): String {
        model.addAttribute("hierarchy", conceptHierarchyService.getHierarchySummary("OCONECO"))
        return "conceptHierarchy/oconeco"
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
