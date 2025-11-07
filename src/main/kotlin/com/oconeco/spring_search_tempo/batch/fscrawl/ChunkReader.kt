package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * ItemReader that reads FSFileDTO entities with non-null bodyText for chunking.
 *
 * Uses service layer pagination to efficiently process files in batches, avoiding loading
 * all files into memory at once.
 *
 * @param fileService Service for accessing FSFile entities
 * @param pageSize Number of files to load per page (default 50)
 */
class ChunkReader(
    private val fileService: FSFileService,
    private val pageSize: Int = 50
) : ItemReader<FSFileDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(ChunkReader::class.java)
    }

    private var currentPage = 0
    private var currentPageData: List<FSFileDTO> = emptyList()
    private var currentIndex = 0
    private var totalFilesProcessed = 0
    private var initialized = false
    private var totalFiles: Long = 0

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

        // Get first page to determine total count
        val firstPage = fileService.findFilesWithBodyText(
            PageRequest.of(0, pageSize, Sort.by("id"))
        )
        totalFiles = firstPage.totalElements

        log.info("Found {} files with bodyText to chunk", totalFiles)

        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        log.debug("Loading page {} with page size {}", currentPage, pageSize)

        val page = fileService.findFilesWithBodyText(
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
