package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext

/**
 * ItemReader that reads FSFile entities with non-null bodyText for chunking.
 *
 * Uses JPA pagination to efficiently process files in batches, avoiding loading
 * all files into memory at once.
 *
 * @param fileRepository Repository for accessing FSFile entities
 * @param pageSize Number of files to load per page (default 50)
 */
class ChunkReader(
    private val fileRepository: FSFileRepository,
    private val pageSize: Int = 50
) : ItemReader<FSFile> {

    companion object {
        private val log = LoggerFactory.getLogger(ChunkReader::class.java)
    }

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private var currentPage = 0
    private var currentPageData: List<FSFile> = emptyList()
    private var currentIndex = 0
    private var totalFilesProcessed = 0
    private var initialized = false

    override fun read(): FSFile? {
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

        // Count files with bodyText
        val query = entityManager.createQuery(
            "SELECT COUNT(f) FROM FSFile f WHERE f.bodyText IS NOT NULL AND LENGTH(f.bodyText) > 0",
            Long::class.java
        )
        val totalFiles = query.singleResult

        log.info("Found {} files with bodyText to chunk", totalFiles)

        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        log.debug("Loading page {} with page size {}", currentPage, pageSize)

        // Use EntityManager for more control over the query
        val query = entityManager.createQuery(
            "SELECT f FROM FSFile f WHERE f.bodyText IS NOT NULL AND LENGTH(f.bodyText) > 0 ORDER BY f.id",
            FSFile::class.java
        )
        query.firstResult = currentPage * pageSize
        query.maxResults = pageSize

        currentPageData = query.resultList
        currentIndex = 0
        currentPage++

        if (currentPageData.isNotEmpty()) {
            log.debug("Loaded {} files on page {}", currentPageData.size, currentPage - 1)
        } else {
            log.debug("No more files to load")
        }
    }
}
