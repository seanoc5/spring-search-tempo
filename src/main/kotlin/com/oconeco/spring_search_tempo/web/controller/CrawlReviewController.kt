package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.CrawlReviewService
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.model.FolderMatchCategory
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.nio.file.Files
import java.nio.file.Path

/**
 * Controller for crawl review tool - comparing filesystem state against database.
 */
@Controller
@RequestMapping("/crawlReview")
class CrawlReviewController(
    private val crawlReviewService: CrawlReviewService,
    private val crawlConfigService: DatabaseCrawlConfigService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Show crawl review landing page with config selection.
     */
    @GetMapping
    fun list(model: Model): String {
        val configs = crawlConfigService.findAllEnabled()
        model.addAttribute("configs", configs)
        return "crawlReview/list"
    }

    /**
     * Compare folders from uploaded file against DB for a crawl config.
     */
    @PostMapping("/{configId}/folders")
    fun compareFolders(
        @PathVariable configId: Long,
        @RequestParam("fsListFile") file: MultipartFile,
        @RequestParam(name = "category", required = false) categoryFilter: String?,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        if (file.isEmpty) {
            redirectAttributes.addFlashAttribute("error", "Please upload a file containing folder paths")
            return "redirect:/crawlReview"
        }

        try {
            val tempFile = saveUploadedFile(file)
            val (summary, items) = crawlReviewService.compareFolders(tempFile, configId)

            // Apply category filter if specified
            val filteredItems = if (categoryFilter != null) {
                val category = FolderMatchCategory.valueOf(categoryFilter)
                items.filter { it.category == category }
            } else {
                items
            }

            model.addAttribute("summary", summary)
            model.addAttribute("items", filteredItems)
            model.addAttribute("allItems", items)
            model.addAttribute("categoryFilter", categoryFilter)

            // Clean up temp file
            Files.deleteIfExists(tempFile)

            return "crawlReview/folders"
        } catch (e: Exception) {
            log.error("Error comparing folders for config {}", configId, e)
            redirectAttributes.addFlashAttribute("error", "Error comparing folders: ${e.message}")
            return "redirect:/crawlReview"
        }
    }

    /**
     * Compare files from uploaded file against DB for a crawl config.
     */
    @PostMapping("/{configId}/files")
    fun compareFiles(
        @PathVariable configId: Long,
        @RequestParam("fsListFile") file: MultipartFile,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        if (file.isEmpty) {
            redirectAttributes.addFlashAttribute("error", "Please upload a file containing file paths")
            return "redirect:/crawlReview"
        }

        try {
            val tempFile = saveUploadedFile(file)
            val summary = crawlReviewService.compareFiles(tempFile, configId)

            model.addAttribute("summary", summary)

            // Clean up temp file
            Files.deleteIfExists(tempFile)

            return "crawlReview/files"
        } catch (e: Exception) {
            log.error("Error comparing files for config {}", configId, e)
            redirectAttributes.addFlashAttribute("error", "Error comparing files: ${e.message}")
            return "redirect:/crawlReview"
        }
    }

    /**
     * Ad-hoc review of a specific folder's immediate children.
     */
    @GetMapping("/folder/{folderId}")
    fun reviewFolder(
        @PathVariable folderId: Long,
        model: Model,
        request: HttpServletRequest
    ): String {
        try {
            val result = crawlReviewService.reviewFolder(folderId)
            model.addAttribute("result", result)

            // Support HTMX partial rendering
            val isHtmx = request.getHeader("HX-Request") == "true"
            val isBoosted = request.getHeader("HX-Boosted") == "true"
            return if (isHtmx && !isBoosted) {
                "crawlReview/folderDetail :: content"
            } else {
                "crawlReview/folderDetail"
            }
        } catch (e: Exception) {
            log.error("Error reviewing folder {}", folderId, e)
            model.addAttribute("error", "Error reviewing folder: ${e.message}")
            return "crawlReview/folderDetail"
        }
    }

    /**
     * Save uploaded file to a temporary location.
     */
    private fun saveUploadedFile(file: MultipartFile): Path {
        val tempFile = Files.createTempFile("crawl-review-", ".txt")
        file.transferTo(tempFile)
        return tempFile
    }
}
