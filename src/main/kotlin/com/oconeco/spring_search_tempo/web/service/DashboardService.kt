package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.web.model.CrawlConfigRowMetrics
import com.oconeco.spring_search_tempo.web.model.CrawlConfigFacet
import com.oconeco.spring_search_tempo.web.model.CrawlConfigSummary
import com.oconeco.spring_search_tempo.web.model.DashboardStatsDTO
import com.oconeco.spring_search_tempo.web.model.FolderRowMetrics
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StopWatch

@Service
class DashboardService(
    private val fileService: FSFileService,
    private val folderService: FSFolderService,
    private val crawlConfigService: DatabaseCrawlConfigService,
    private val fileRepository: FSFileRepository,
    private val folderRepository: FSFolderRepository,
    private val chunkRepository: ContentChunkRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Get all dashboard statistics.
     */
    fun getStats(): DashboardStatsDTO {
        val stopWatch = StopWatch("DashboardStats")
        val stats = DashboardStatsDTO()

        fun <T> timed(name: String, block: () -> T): T {
            stopWatch.start(name)
            return block().also { stopWatch.stop() }
        }

        // Overall counts
        stats.totalFiles = timed("file.count") { fileService.count() }
        stats.totalFolders = timed("folder.count") { folderService.count() }
        stats.totalConfigs = timed("config.count") { crawlConfigService.count() }

        // File counts by status (processing state)
        val fileStatusCounts = timed("file.countByStatus") {
            toCountMap(fileRepository.countGroupedByStatus())
        }
        stats.filesStatusNew = fileStatusCounts["NEW"] ?: 0
        stats.filesStatusInProgress = fileStatusCounts["IN_PROGRESS"] ?: 0
        stats.filesStatusDirty = fileStatusCounts["DIRTY"] ?: 0
        stats.filesStatusCurrent = fileStatusCounts["CURRENT"] ?: 0
        stats.filesStatusFailed = fileStatusCounts["FAILED"] ?: 0

        // File counts by analysis status (processing level)
        val fileAnalysisCounts = timed("file.countByAnalysis") {
            toCountMap(fileRepository.countGroupedByAnalysisStatus())
        }
        stats.filesSkip = fileAnalysisCounts["SKIP"] ?: 0
        stats.filesLocate = fileAnalysisCounts["LOCATE"] ?: 0
        stats.filesIndex = fileAnalysisCounts["INDEX"] ?: 0
        stats.filesAnalyze = fileAnalysisCounts["ANALYZE"] ?: 0
        stats.filesSemantic = fileAnalysisCounts["SEMANTIC"] ?: 0

        // Folder counts by status (processing state)
        val folderStatusCounts = timed("folder.countByStatus") {
            toCountMap(folderRepository.countGroupedByStatus())
        }
        stats.foldersStatusNew = folderStatusCounts["NEW"] ?: 0
        stats.foldersStatusInProgress = folderStatusCounts["IN_PROGRESS"] ?: 0
        stats.foldersStatusDirty = folderStatusCounts["DIRTY"] ?: 0
        stats.foldersStatusCurrent = folderStatusCounts["CURRENT"] ?: 0
        stats.foldersStatusFailed = folderStatusCounts["FAILED"] ?: 0

        // Folder counts by analysis status (processing level)
        val folderAnalysisCounts = timed("folder.countByAnalysis") {
            toCountMap(folderRepository.countGroupedByAnalysisStatus())
        }
        stats.foldersSkip = folderAnalysisCounts["SKIP"] ?: 0
        stats.foldersLocate = folderAnalysisCounts["LOCATE"] ?: 0
        stats.foldersIndex = folderAnalysisCounts["INDEX"] ?: 0
        stats.foldersAnalyze = folderAnalysisCounts["ANALYZE"] ?: 0
        stats.foldersSemantic = folderAnalysisCounts["SEMANTIC"] ?: 0

        // Chunk counts by status (processing state)
        val chunkStatusCounts = timed("chunk.countByStatus") {
            toCountMap(chunkRepository.countGroupedByStatus())
        }
        stats.chunksStatusNew = chunkStatusCounts["NEW"] ?: 0
        stats.chunksStatusInProgress = chunkStatusCounts["IN_PROGRESS"] ?: 0
        stats.chunksStatusDirty = chunkStatusCounts["DIRTY"] ?: 0
        stats.chunksStatusCurrent = chunkStatusCounts["CURRENT"] ?: 0
        stats.chunksStatusFailed = chunkStatusCounts["FAILED"] ?: 0

        // Chunk dashboard counts in one aggregate query.
        val chunkCounts = timed("chunk.counts") { chunkRepository.countDashboardAggregates() }
        val totalChunks = toLong(chunkCounts.getOrNull(0))
        val nlpProcessedCount = toLong(chunkCounts.getOrNull(1))
        val embeddedCount = toLong(chunkCounts.getOrNull(2))
        val nlpPendingCount = toLong(chunkCounts.getOrNull(3))

        stats.totalChunks = totalChunks
        stats.chunksLevelIndex = (totalChunks - nlpProcessedCount).coerceAtLeast(0)
        stats.chunksLevelNlp = (nlpProcessedCount - embeddedCount).coerceAtLeast(0)
        stats.chunksLevelEmbed = embeddedCount
        stats.chunksNlpComplete = nlpProcessedCount
        stats.chunksNlpPending = nlpPendingCount

        // File facets by crawl config (top 20)
        val fileFacets = timed("file.facets") { fileService.countByCrawlConfigFacets() }
        stats.fileFacets = fileFacets
            .take(20)
            .map { (configId, configName, count) ->
                CrawlConfigFacet().apply {
                    this.configId = configId
                    this.configName = configName
                    this.count = count
                }
            }

        // Folder facets by crawl config (top 20)
        val folderFacets = timed("folder.facets") { folderService.countByCrawlConfigFacets() }
        stats.folderFacets = folderFacets
            .take(20)
            .map { (configId, configName, count) ->
                CrawlConfigFacet().apply {
                    this.configId = configId
                    this.configName = configName
                    this.count = count
                }
            }

        // Crawl config summaries with file/folder counts (top 20)
        val fileSkipCounts = timed("file.skipCounts") { fileService.countSkippedByCrawlConfig() }
        val folderSkipCounts = timed("folder.skipCounts") { folderService.countSkippedByCrawlConfig() }

        // Build maps of counts by config ID
        val fileCrawledMap = fileFacets.associate { it.first to it.third }
        val folderCrawledMap = folderFacets.associate { it.first to it.third }

        // Get all configs and build summaries
        val configs = timed("config.findAll") { crawlConfigService.findAll(null, PageRequest.of(0, 20)) }
        stats.crawlConfigSummaries = configs.content.map { config ->
            CrawlConfigSummary().apply {
                configId = config.id!!
                configName = config.name ?: "Unknown"
                enabled = config.enabled
                filesCrawled = fileCrawledMap[config.id] ?: 0
                filesSkipped = fileSkipCounts[config.id] ?: 0
                foldersCrawled = folderCrawledMap[config.id] ?: 0
                foldersSkipped = folderSkipCounts[config.id] ?: 0
            }
        }

        log.trace("DashboardStats timing:\n{}", stopWatch.prettyPrint())

        return stats
    }

    @Transactional(readOnly = true)
    fun getCrawlConfigRowMetrics(configIds: Collection<Long>): Map<Long, CrawlConfigRowMetrics> {
        if (configIds.isEmpty()) {
            return emptyMap()
        }

        val fileCounts = fileRepository.countTotalGroupedByCrawlConfigIds(configIds)
            .associate { toLong(it[0]) to toLong(it[1]) }
        val folderCounts = folderRepository.countTotalGroupedByCrawlConfigIds(configIds)
            .associate { toLong(it[0]) to toLong(it[1]) }
        val fileSizes = fileRepository.sumSizeGroupedByCrawlConfigIds(configIds)
            .associate { toLong(it[0]) to toLong(it[1]) }

        return configIds.associateWith { configId ->
            CrawlConfigRowMetrics(
                folderCount = folderCounts[configId] ?: 0,
                fileCount = fileCounts[configId] ?: 0,
                totalFileSize = fileSizes[configId] ?: 0
            )
        }
    }

    @Transactional(readOnly = true)
    fun getFolderRowMetrics(folderIds: Collection<Long>): Map<Long, FolderRowMetrics> {
        if (folderIds.isEmpty()) {
            return emptyMap()
        }
        return folderRepository.findDashboardFolderMetrics(folderIds).associate { row ->
            val folderId = toLong(row[0])
            folderId to FolderRowMetrics(
                directFolderCount = toLong(row[1]),
                recursiveFolderCount = toLong(row[2]),
                directFileCount = toLong(row[3]),
                recursiveFileCount = toLong(row[4]),
                totalFileSize = toLong(row[5])
            )
        }
    }

    @Transactional(readOnly = true)
    fun getFileFragmentCounts(fileIds: Collection<Long>): Map<Long, Long> {
        if (fileIds.isEmpty()) {
            return emptyMap()
        }
        return chunkRepository.countGroupedByFileIds(fileIds)
            .associate { toLong(it[0]) to toLong(it[1]) }
    }

    private fun toLong(value: Any?): Long {
        return when (value) {
            null -> 0
            is Long -> value
            is Int -> value.toLong()
            is java.math.BigInteger -> value.toLong()
            is java.math.BigDecimal -> value.toLong()
            is Number -> value.toLong()
            else -> value.toString().toLongOrNull() ?: 0
        }
    }

    private fun toCountMap(rows: List<Array<Any?>>): Map<String, Long> {
        return rows.mapNotNull { row ->
            val key = row.getOrNull(0)?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to toLong(row.getOrNull(1))
        }.toMap()
    }
}
