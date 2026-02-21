package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Implementation of CrawlDataCleanupService that deletes crawl data
 * in the correct order to respect foreign key constraints.
 */
@Service
class CrawlDataCleanupServiceImpl(
    private val contentChunkRepository: ContentChunkRepository,
    private val fsFileRepository: FSFileRepository,
    private val fsFolderRepository: FSFolderRepository
) : CrawlDataCleanupService {

    companion object {
        private val log = LoggerFactory.getLogger(CrawlDataCleanupServiceImpl::class.java)
    }

    @Transactional
    override fun deleteAllDataForCrawlConfig(crawlConfigId: Long): CleanupSummary {
        log.info("Starting cleanup for crawl config: {}", crawlConfigId)

        // Delete in order to respect FK constraints:
        // 1. ContentChunks (references FSFile via concept_id)
        val chunksDeleted = contentChunkRepository.deleteByCrawlConfigId(crawlConfigId)
        log.info("Deleted {} content chunks for crawl config {}", chunksDeleted, crawlConfigId)

        // 2. FSFiles (references FSFolder via fs_folder_id)
        val filesDeleted = fsFileRepository.deleteByCrawlConfigId(crawlConfigId)
        log.info("Deleted {} files for crawl config {}", filesDeleted, crawlConfigId)

        // 3. FSFolders (no remaining FK references)
        val foldersDeleted = fsFolderRepository.deleteByCrawlConfigId(crawlConfigId)
        log.info("Deleted {} folders for crawl config {}", foldersDeleted, crawlConfigId)

        val summary = CleanupSummary(
            chunksDeleted = chunksDeleted,
            filesDeleted = filesDeleted,
            foldersDeleted = foldersDeleted
        )

        log.info("Cleanup complete for crawl config {}: {} total items deleted (chunks={}, files={}, folders={})",
            crawlConfigId, summary.totalDeleted, chunksDeleted, filesDeleted, foldersDeleted)

        return summary
    }
}
