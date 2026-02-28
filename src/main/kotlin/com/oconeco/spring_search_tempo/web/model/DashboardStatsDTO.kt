package com.oconeco.spring_search_tempo.web.model

/**
 * DTO for dashboard statistics.
 */
class DashboardStatsDTO {
    // Overall counts
    var totalFiles: Long = 0
    var totalFolders: Long = 0
    var totalChunks: Long = 0
    var totalConfigs: Long = 0

    // File counts by status (processing state)
    var filesStatusNew: Long = 0
    var filesStatusInProgress: Long = 0
    var filesStatusDirty: Long = 0
    var filesStatusCurrent: Long = 0
    var filesStatusFailed: Long = 0

    // File counts by analysis status (processing level)
    var filesSkip: Long = 0
    var filesLocate: Long = 0
    var filesIndex: Long = 0
    var filesAnalyze: Long = 0
    var filesSemantic: Long = 0

    // Folder counts by status (processing state)
    var foldersStatusNew: Long = 0
    var foldersStatusInProgress: Long = 0
    var foldersStatusDirty: Long = 0
    var foldersStatusCurrent: Long = 0
    var foldersStatusFailed: Long = 0

    // Folder counts by analysis status (processing level)
    var foldersSkip: Long = 0
    var foldersLocate: Long = 0
    var foldersIndex: Long = 0
    var foldersAnalyze: Long = 0
    var foldersSemantic: Long = 0

    // Chunk counts by status
    var chunksStatusNew: Long = 0
    var chunksStatusInProgress: Long = 0
    var chunksStatusDirty: Long = 0
    var chunksStatusCurrent: Long = 0
    var chunksStatusFailed: Long = 0

    // Chunk counts by analysis level
    var chunksLevelIndex: Long = 0   // Default: text indexed only
    var chunksLevelNlp: Long = 0     // INDEX + NLP processing done
    var chunksLevelEmbed: Long = 0   // INDEX + NLP + vector embedding

    // Chunk processing counts (legacy, for NLP progress bar)
    var chunksNlpPending: Long = 0
    var chunksNlpComplete: Long = 0

    // Facet counts by crawl config (first 20)
    var fileFacets: List<CrawlConfigFacet> = emptyList()
    var folderFacets: List<CrawlConfigFacet> = emptyList()

    // Detailed crawl config summaries (first 20)
    var crawlConfigSummaries: List<CrawlConfigSummary> = emptyList()
}

/**
 * Facet count for a crawl config.
 */
class CrawlConfigFacet {
    var configId: Long = 0
    var configName: String = ""
    var count: Long = 0
}

/**
 * Detailed summary for a crawl config including file/folder counts.
 */
class CrawlConfigSummary {
    var configId: Long = 0
    var configName: String = ""
    var enabled: Boolean = true
    var filesCrawled: Long = 0
    var filesSkipped: Long = 0
    var foldersCrawled: Long = 0
    var foldersSkipped: Long = 0
}
