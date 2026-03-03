package com.oconeco.spring_search_tempo.batch.assignment

import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * Reader that fetches folders needing analysis status assignment.
 *
 * Reads folders where:
 * - analysisStatusSetBy is "DEFAULT" (not yet assigned by patterns)
 * - OR analysisStatusSetBy is null (legacy data)
 *
 * Orders by URI to ensure parents are processed before children,
 * enabling proper inheritance.
 */
class FolderAssignmentReader(
    private val folderRepository: FSFolderRepository,
    private val pageSize: Int = 100
) : ItemReader<FolderAssignmentItem> {

    companion object {
        private val log = LoggerFactory.getLogger(FolderAssignmentReader::class.java)
    }

    private var currentPage = 0
    private var currentPageData: List<FolderAssignmentItem> = emptyList()
    private var currentIndex = 0
    private var initialized = false
    private var totalFolders: Long = 0

    @Synchronized
    override fun read(): FolderAssignmentItem? {
        if (!initialized) {
            initialize()
        }

        if (currentIndex >= currentPageData.size) {
            loadNextPage()
            if (currentPageData.isEmpty()) {
                log.info("FolderAssignmentReader completed. Total: {}", totalFolders)
                return null
            }
        }

        return currentPageData[currentIndex++]
    }

    private fun initialize() {
        log.info("Initializing FolderAssignmentReader...")

        val pageable = PageRequest.of(0, 1, Sort.by("uri"))
        val countPage = folderRepository.findFoldersNeedingAssignment(pageable)
        totalFolders = countPage.totalElements

        log.info("Found {} folders needing assignment", totalFolders)
        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        val pageable = PageRequest.of(currentPage, pageSize, Sort.by("uri"))
        val page = folderRepository.findFoldersNeedingAssignment(pageable)

        currentPageData = page.content.map { folder ->
            FolderAssignmentItem(
                id = folder.id!!,
                uri = folder.uri!!,
                parentUri = folder.uri?.substringBeforeLast('/')?.takeIf { it.isNotEmpty() },
                currentStatus = folder.analysisStatus,
                currentSetBy = folder.analysisStatusSetBy
            )
        }
        currentIndex = 0
        currentPage++

        if (currentPageData.isNotEmpty()) {
            log.debug("Loaded {} folders on page {}", currentPageData.size, currentPage - 1)
        }
    }
}
