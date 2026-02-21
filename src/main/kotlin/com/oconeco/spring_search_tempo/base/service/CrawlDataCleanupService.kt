package com.oconeco.spring_search_tempo.base.service

/**
 * Service for cleaning up crawl data before re-crawling.
 * Used when "Delete existing data" option is selected.
 */
interface CrawlDataCleanupService {

    /**
     * Delete all data (chunks, files, folders) associated with a crawl config.
     * Deletes in proper order to respect foreign key constraints:
     * 1. ContentChunks (references FSFile)
     * 2. FSFiles (references FSFolder via fsFolder)
     * 3. FSFolders
     *
     * @param crawlConfigId The ID of the crawl config whose data should be deleted
     * @return Summary of what was deleted
     */
    fun deleteAllDataForCrawlConfig(crawlConfigId: Long): CleanupSummary
}

/**
 * Summary of cleanup operation results.
 */
data class CleanupSummary(
    val chunksDeleted: Int,
    val filesDeleted: Int,
    val foldersDeleted: Int
) {
    val totalDeleted: Int
        get() = chunksDeleted + filesDeleted + foldersDeleted

    val isEmpty: Boolean
        get() = totalDeleted == 0
}
