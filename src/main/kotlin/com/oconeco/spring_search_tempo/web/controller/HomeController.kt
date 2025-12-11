package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.JobRunService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping


@Controller
class HomeController(
    private val fileService: FSFileService,
    private val folderService: FSFolderService,
    private val chunkService: ContentChunkService,
    private val crawlConfigService: DatabaseCrawlConfigService,
    private val jobRunService: JobRunService
) {

    @GetMapping("/")
    fun index(model: Model): String {
        // Get counts for dashboard stats
        model.addAttribute("fileCount", fileService.count())
        model.addAttribute("folderCount", folderService.count())
        model.addAttribute("chunkCount", chunkService.count())
        model.addAttribute("configCount", crawlConfigService.count())

        // Get recent job runs for activity feed (last 5)
        val recentJobRuns = jobRunService.findAll(
            null,
            PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "startTime"))
        ).content
        model.addAttribute("recentJobRuns", recentJobRuns)

        return "home/index"
    }

}
