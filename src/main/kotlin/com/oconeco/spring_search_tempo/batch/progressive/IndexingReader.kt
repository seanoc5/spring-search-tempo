package com.oconeco.spring_search_tempo.batch.progressive

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * Reader for files needing text extraction (indexing).
 *
 * Reads files where:
 * - analysisStatus is INDEX, ANALYZE, or SEMANTIC
 * - indexedAt is null (never indexed) OR lastUpdated > indexedAt (modified since)
 *
 * This is the second step in progressive analysis:
 * 1. Discovery - Enumerate filesystem
 * 2. Assignment - Assign analysisStatus
 * 3. **Indexing (this)** - Tika text extraction
 * 4. Chunking - Split into chunks
 * 5. NLP - Entity extraction, sentiment
 * 6. Embedding - Vector generation
 */
class IndexingReader(
    private val fileRepository: FSFileRepository,
    private val fileMapper: FSFileMapper,
    private val pageSize: Int = 50
) : ItemReader<FSFileDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(IndexingReader::class.java)

        // Analysis status levels that need indexing
        val INDEXING_STATUSES = listOf(
            AnalysisStatus.INDEX,
            AnalysisStatus.ANALYZE,
            AnalysisStatus.SEMANTIC
        )
    }

    private var currentPage = 0
    private var currentPageData: List<FSFileDTO> = emptyList()
    private var currentIndex = 0
    private var initialized = false
    private var totalFiles: Long = 0
    private var processedCount = 0

    @Synchronized
    override fun read(): FSFileDTO? {
        if (!initialized) {
            initialize()
        }

        if (currentIndex >= currentPageData.size) {
            loadNextPage()
            if (currentPageData.isEmpty()) {
                log.info("IndexingReader completed. Processed: {}/{}", processedCount, totalFiles)
                return null
            }
        }

        processedCount++
        if (processedCount % 100 == 0) {
            log.info("IndexingReader progress: {}/{}", processedCount, totalFiles)
        }

        return currentPageData[currentIndex++]
    }

    private fun initialize() {
        log.info("Initializing IndexingReader...")

        val pageable = PageRequest.of(0, 1, Sort.by("id"))
        val countPage = fileRepository.findFilesNeedingIndexing(INDEXING_STATUSES, pageable)
        totalFiles = countPage.totalElements

        log.info("Found {} files needing indexing", totalFiles)
        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        val pageable = PageRequest.of(currentPage, pageSize, Sort.by("id"))
        val page = fileRepository.findFilesNeedingIndexing(INDEXING_STATUSES, pageable)

        currentPageData = page.content.map { file ->
            val dto = FSFileDTO()
            fileMapper.updateFSFileDTO(file, dto)
            dto
        }
        currentIndex = 0
        currentPage++

        if (currentPageData.isNotEmpty()) {
            log.debug("Loaded {} files on page {}", currentPageData.size, currentPage - 1)
        }
    }
}
