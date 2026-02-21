package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * ItemReader that reads FSFileDTO entities needing chunking.
 *
 * Only reads files where:
 * - They belong to the current job run (scoped to this crawl)
 * - bodyText is not null, AND
 * - chunkedAt is null (never chunked) OR lastUpdated > chunkedAt (modified since chunking)
 *
 * This prevents re-processing already-chunked files and scopes chunking to files
 * from the current crawl job only.
 *
 * Implements StepExecutionListener to get the jobRunId from the step execution context.
 *
 * @param fileService Service for accessing FSFile entities
 * @param pageSize Number of files to load per page (default 50)
 */
class ChunkReader(
    private val fileService: FSFileService,
    private val pageSize: Int = 50
) : ItemReader<FSFileDTO>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(ChunkReader::class.java)
    }

    private var currentPage = 0
    private var currentPageData: List<FSFileDTO> = emptyList()
    private var currentIndex = 0
    private var totalFilesProcessed = 0
    private var initialized = false
    private var totalFiles: Long = 0
    private var jobRunId: Long? = null

    override fun beforeStep(stepExecution: StepExecution) {
        // Get jobRunId from job execution context (set by JobRunTrackingListener)
        jobRunId = stepExecution.jobExecution.executionContext
            .getLong(JobRunTrackingListener.JOB_RUN_ID_KEY, -1L)
            .takeIf { it > 0 }

        log.info("ChunkReader initialized with jobRunId: {}", jobRunId)
    }

    override fun read(): FSFileDTO? {
        if (!initialized) {
            initialize()
        }

        // If we've exhausted the current page, load the next one
        if (currentIndex >= currentPageData.size) {
            loadNextPage()

            // If still no data, we're done
            if (currentPageData.isEmpty()) {
                log.info("ChunkReader completed. Total files processed: {}", totalFilesProcessed)
                return null
            }
        }

        val file = currentPageData[currentIndex]
        currentIndex++
        totalFilesProcessed++

        if (totalFilesProcessed % 50 == 0) {
            log.info("ChunkReader progress: {} files processed", totalFilesProcessed)
        }

        return file
    }

    private fun initialize() {
        log.info("Initializing ChunkReader...")

        if (jobRunId == null) {
            log.warn("No jobRunId available - chunking will be skipped. This may happen if the crawl step failed.")
            totalFiles = 0
            initialized = true
            return
        }

        // Get first page to determine total count - only files from this job run needing chunking
        val firstPage = fileService.findFilesNeedingChunkingByJobRunId(
            jobRunId!!,
            PageRequest.of(0, pageSize, Sort.by("id"))
        )
        totalFiles = firstPage.totalElements

        if (totalFiles == 0L) {
            log.info("No files need chunking for job run {} (all files are up-to-date or have no text)", jobRunId)
        } else {
            log.info("Found {} files needing chunking for job run {} (new or modified since last chunking)", totalFiles, jobRunId)
        }

        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        if (jobRunId == null) {
            currentPageData = emptyList()
            return
        }

        log.debug("Loading page {} with page size {}", currentPage, pageSize)

        val page = fileService.findFilesNeedingChunkingByJobRunId(
            jobRunId!!,
            PageRequest.of(currentPage, pageSize, Sort.by("id"))
        )

        currentPageData = page.content
        currentIndex = 0
        currentPage++

        if (currentPageData.isNotEmpty()) {
            log.debug("Loaded {} files on page {}", currentPageData.size, currentPage - 1)
        } else {
            log.debug("No more files to load")
        }
    }
}
