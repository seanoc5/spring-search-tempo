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
 * By default (processAll=false), only reads files where:
 * - They belong to the current job run (scoped to this crawl)
 * - bodyText is not null, AND
 * - chunkedAt is null (never chunked) OR lastUpdated > chunkedAt (modified since chunking)
 *
 * When processAll=true, reads ALL files needing chunking regardless of job run.
 * This is useful for:
 * - Backfilling chunks for files indexed before chunking was integrated
 * - Recovery after failed chunking steps
 * - One-time catchup processing
 *
 * Implements StepExecutionListener to get the jobRunId from the step execution context.
 *
 * @param fileService Service for accessing FSFile entities
 * @param pageSize Number of files to load per page (default 50)
 * @param processAll When true, process ALL files needing chunking regardless of job run
 */
class ChunkReader(
    private val fileService: FSFileService,
    private val pageSize: Int = 50,
    private val processAll: Boolean = false
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

        // Explicitly initialize folder stats to 0 to prevent accumulation from other steps.
        // The chunking step doesn't process folders, so these must be zero.
        // Without this, JobRunTrackingListener.afterJob() may sum stale/inherited values.
        stepExecution.executionContext.putLong("foldersDiscovered", 0L)
        stepExecution.executionContext.putLong("foldersNew", 0L)
        stepExecution.executionContext.putLong("foldersUpdated", 0L)
        stepExecution.executionContext.putLong("foldersSkipped", 0L)

        if (processAll) {
            log.info("ChunkReader initialized in processAll mode - will chunk ALL files needing chunking")
        } else {
            log.info("ChunkReader initialized with jobRunId: {}", jobRunId)
        }
    }

    @Synchronized
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
        log.info("Initializing ChunkReader (processAll={})...", processAll)

        val pageable = PageRequest.of(0, pageSize, Sort.by("id"))

        if (processAll) {
            // Process ALL files needing chunking, regardless of job run
            val firstPage = fileService.findFilesNeedingChunking(pageable)
            totalFiles = firstPage.totalElements

            if (totalFiles == 0L) {
                log.info("No files need chunking globally (all files are up-to-date or have no text)")
            } else {
                log.info("Found {} files needing chunking globally (processAll mode)", totalFiles)
            }
        } else {
            // Scoped to current job run
            if (jobRunId == null) {
                log.warn("No jobRunId available - chunking will be skipped. This may happen if the crawl step failed.")
                totalFiles = 0
                initialized = true
                return
            }

            val firstPage = fileService.findFilesNeedingChunkingByJobRunId(jobRunId!!, pageable)
            totalFiles = firstPage.totalElements

            if (totalFiles == 0L) {
                log.info("No files need chunking for job run {} (all files are up-to-date or have no text)", jobRunId)
            } else {
                log.info("Found {} files needing chunking for job run {} (new or modified since last chunking)", totalFiles, jobRunId)
            }
        }

        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        if (!processAll && jobRunId == null) {
            currentPageData = emptyList()
            return
        }

        log.debug("Loading page {} with page size {}", currentPage, pageSize)

        val pageable = PageRequest.of(currentPage, pageSize, Sort.by("id"))
        val page = if (processAll) {
            fileService.findFilesNeedingChunking(pageable)
        } else {
            fileService.findFilesNeedingChunkingByJobRunId(jobRunId!!, pageable)
        }

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
