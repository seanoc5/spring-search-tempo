package com.oconeco.spring_search_tempo.batch.assignment

import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * Reader that fetches files needing analysis status assignment.
 *
 * Reads files where:
 * - analysisStatusSetBy is "DEFAULT" (not yet assigned by patterns)
 * - OR analysisStatusSetBy is null (legacy data)
 */
class FileAssignmentReader(
    private val fileRepository: FSFileRepository,
    private val pageSize: Int = 100
) : ItemReader<FileAssignmentItem> {

    companion object {
        private val log = LoggerFactory.getLogger(FileAssignmentReader::class.java)
    }

    private var currentPage = 0
    private var currentPageData: List<FileAssignmentItem> = emptyList()
    private var currentIndex = 0
    private var initialized = false
    private var totalFiles: Long = 0

    @Synchronized
    override fun read(): FileAssignmentItem? {
        if (!initialized) {
            initialize()
        }

        if (currentIndex >= currentPageData.size) {
            loadNextPage()
            if (currentPageData.isEmpty()) {
                log.info("FileAssignmentReader completed. Total: {}", totalFiles)
                return null
            }
        }

        return currentPageData[currentIndex++]
    }

    private fun initialize() {
        log.info("Initializing FileAssignmentReader...")

        val pageable = PageRequest.of(0, 1, Sort.by("id"))
        val countPage = fileRepository.findFilesNeedingAssignment(pageable)
        totalFiles = countPage.totalElements

        log.info("Found {} files needing assignment", totalFiles)
        initialized = true
        loadNextPage()
    }

    private fun loadNextPage() {
        val pageable = PageRequest.of(currentPage, pageSize, Sort.by("id"))
        val page = fileRepository.findFilesNeedingAssignment(pageable)

        currentPageData = page.content.map { file ->
            FileAssignmentItem(
                id = file.id!!,
                uri = file.uri!!,
                parentFolderId = file.fsFolder?.id,
                parentFolderUri = file.fsFolder?.uri,
                currentStatus = file.analysisStatus,
                currentSetBy = file.analysisStatusSetBy
            )
        }
        currentIndex = 0
        currentPage++

        if (currentPageData.isNotEmpty()) {
            log.debug("Loaded {} files on page {}", currentPageData.size, currentPage - 1)
        }
    }
}
