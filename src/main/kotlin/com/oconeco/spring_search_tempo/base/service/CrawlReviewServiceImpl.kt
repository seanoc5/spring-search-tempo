package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.CrawlReviewService
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.CrawlConfiguration
import com.oconeco.spring_search_tempo.base.config.PatternPriority
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.model.*
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.regex.Pattern
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Implementation of CrawlReviewService for comparing filesystem state against database.
 */
@Service
@Transactional(readOnly = true)
class CrawlReviewServiceImpl(
    private val fsFolderRepository: FSFolderRepository,
    private val fsFileRepository: FSFileRepository,
    private val crawlConfigService: DatabaseCrawlConfigService,
    private val crawlConfiguration: CrawlConfiguration,
    private val patternMatchingService: PatternMatchingService,
    @Qualifier("FSFolderMapperImpl") private val fsFolderMapper: FSFolderMapper,
    @Qualifier("FSFileMapperImpl") private val fsFileMapper: FSFileMapper
) : CrawlReviewService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun compareFolders(
        fsListPath: Path,
        crawlConfigId: Long
    ): Pair<FolderComparisonSummary, List<FolderComparisonItem>> {
        val config = crawlConfigService.get(crawlConfigId)
        val configName = config.name ?: "Unknown"

        // Load filesystem paths from uploaded file
        val fsPaths = loadPathsFromFile(fsListPath)
            .filter { isWithinStartPaths(it, config.startPaths ?: emptyList()) }
            .toSet()

        // Load all DB folders for this config
        val dbFolders = fsFolderRepository.findAllByCrawlConfigId(crawlConfigId)
        val dbUris = dbFolders.associateBy { it.uri ?: "" }

        // Build effective patterns for this config
        val folderPatterns = buildFolderPatterns(config)

        // Categorize each path
        val items = mutableListOf<FolderComparisonItem>()

        // Check FS paths against DB
        for (fsPath in fsPaths) {
            val dbFolder = dbUris[fsPath]
            if (dbFolder != null) {
                items.add(
                    FolderComparisonItem(
                        path = fsPath,
                        category = FolderMatchCategory.BOTH_EXIST,
                        dbAnalysisStatus = dbFolder.analysisStatus,
                        dbLastUpdated = dbFolder.lastUpdated
                    )
                )
            } else {
                // Not in DB - check if expected (SKIP pattern match) or unexpected
                val matchedPattern = findMatchingPattern(fsPath, folderPatterns.skip)
                if (matchedPattern != null) {
                    items.add(
                        FolderComparisonItem(
                            path = fsPath,
                            category = FolderMatchCategory.FS_ONLY_EXPECTED,
                            matchedPattern = matchedPattern
                        )
                    )
                } else {
                    items.add(
                        FolderComparisonItem(
                            path = fsPath,
                            category = FolderMatchCategory.FS_ONLY_UNEXPECTED
                        )
                    )
                }
            }
        }

        // Check DB folders not in FS
        for ((uri, folder) in dbUris) {
            if (uri !in fsPaths) {
                items.add(
                    FolderComparisonItem(
                        path = uri,
                        category = FolderMatchCategory.DB_ONLY,
                        dbAnalysisStatus = folder.analysisStatus,
                        dbLastUpdated = folder.lastUpdated
                    )
                )
            }
        }

        // Sort items by path for easier review
        val sortedItems = items.sortedBy { it.path }

        // Build summary
        val summary = FolderComparisonSummary(
            crawlConfigId = crawlConfigId,
            crawlConfigName = configName,
            totalFsCount = fsPaths.size,
            totalDbCount = dbFolders.size,
            bothExistCount = sortedItems.count { it.category == FolderMatchCategory.BOTH_EXIST },
            fsOnlyExpectedCount = sortedItems.count { it.category == FolderMatchCategory.FS_ONLY_EXPECTED },
            fsOnlyUnexpectedCount = sortedItems.count { it.category == FolderMatchCategory.FS_ONLY_UNEXPECTED },
            dbOnlyCount = sortedItems.count { it.category == FolderMatchCategory.DB_ONLY }
        )

        log.info(
            "Folder comparison for config '{}': {} FS, {} DB, {} both, {} expected skip, {} unexpected, {} DB only",
            configName, fsPaths.size, dbFolders.size, summary.bothExistCount,
            summary.fsOnlyExpectedCount, summary.fsOnlyUnexpectedCount, summary.dbOnlyCount
        )

        return Pair(summary, sortedItems)
    }

    override fun compareFiles(
        fsListPath: Path,
        crawlConfigId: Long
    ): FileComparisonSummary {
        val config = crawlConfigService.get(crawlConfigId)
        val configName = config.name ?: "Unknown"

        // Load filesystem paths from uploaded file
        val fsPaths = loadPathsFromFile(fsListPath)
            .filter { isWithinStartPaths(it, config.startPaths ?: emptyList()) }

        // Load all DB files for this config
        val dbFiles = fsFileRepository.findAllByCrawlConfigId(crawlConfigId)
        val dbUris = dbFiles.map { it.uri ?: "" }.toSet()

        // Group FS files by extension
        val fsExtensions = fsPaths.groupBy { extractExtension(it) }
            .mapValues { it.value.size }

        // Group DB files by extension
        val dbExtensions = dbFiles.groupBy { extractExtension(it.uri ?: "") }
            .mapValues { it.value.size }

        // Build extension breakdown
        val allExtensions = (fsExtensions.keys + dbExtensions.keys).toSortedSet()
        val extensionBreakdown = allExtensions.map { ext ->
            val fsCount = fsExtensions[ext] ?: 0
            val dbCount = dbExtensions[ext] ?: 0
            val diff = fsCount - dbCount
            val coverage = if (fsCount > 0) (dbCount.toDouble() / fsCount * 100) else 100.0
            FileExtensionSummary(
                extension = ext.ifEmpty { "(no extension)" },
                fsCount = fsCount,
                dbCount = dbCount,
                difference = diff,
                percentageCovered = coverage
            )
        }.sortedByDescending { it.difference }

        val summary = FileComparisonSummary(
            crawlConfigId = crawlConfigId,
            crawlConfigName = configName,
            totalFsFiles = fsPaths.size,
            totalDbFiles = dbFiles.size,
            extensionBreakdown = extensionBreakdown
        )

        log.info(
            "File comparison for config '{}': {} FS files, {} DB files, {} extensions",
            configName, fsPaths.size, dbFiles.size, extensionBreakdown.size
        )

        return summary
    }

    override fun reviewFolder(folderId: Long): FolderReviewResult {
        // Get folder from DB
        val dbFolder = fsFolderRepository.findById(folderId)
            .orElseThrow { NotFoundException("Folder not found: $folderId") }

        val folderUri = dbFolder.uri ?: throw NotFoundException("Folder has no URI")
        val folderPath = Path.of(folderUri)

        val folderDTO = FSFolderDTO().also { fsFolderMapper.updateFSFolderDTO(dbFolder, it) }

        // Use default patterns for ad-hoc folder review
        // (Future: could look up crawl config from job run if needed)
        val folderPatterns = crawlConfiguration.defaults.folderPatterns
        val filePatterns = crawlConfiguration.defaults.filePatterns
        val folderPriority = crawlConfiguration.defaults.folderPatternPriority
        val filePriority = crawlConfiguration.defaults.filePatternPriority

        // Get immediate children from filesystem
        val fsFiles = mutableListOf<FileReviewItem>()
        val fsFolders = mutableListOf<SubfolderReviewItem>()

        if (folderPath.exists() && folderPath.isDirectory()) {
            Files.list(folderPath).use { stream ->
                stream.forEach { childPath ->
                    val pathStr = childPath.toString()
                    if (childPath.isRegularFile()) {
                        val expectedStatus = patternMatchingService.determineFileAnalysisStatus(
                            pathStr,
                            filePatterns,
                            folderDTO.analysisStatus ?: AnalysisStatus.LOCATE,
                            filePriority
                        )
                        val matchedPattern = findMatchingPatternForFile(pathStr, filePatterns, filePriority)
                        val dbFile = fsFileRepository.findByUri(pathStr)
                        val dbFileDTO = dbFile?.let { FSFileDTO().also { dto -> fsFileMapper.updateFSFileDTO(it, dto) } }

                        fsFiles.add(
                            FileReviewItem(
                                filename = childPath.fileName.toString(),
                                path = pathStr,
                                size = Files.size(childPath),
                                lastModified = Files.getLastModifiedTime(childPath).toInstant(),
                                expectedStatus = expectedStatus,
                                matchedPattern = matchedPattern,
                                existsInDb = dbFile != null,
                                dbRecord = dbFileDTO
                            )
                        )
                    } else if (childPath.isDirectory()) {
                        val expectedStatus = patternMatchingService.determineFolderAnalysisStatus(
                            pathStr,
                            folderPatterns,
                            folderDTO.analysisStatus,
                            folderPriority
                        )
                        val matchedPattern = findMatchingPatternForFolder(pathStr, folderPatterns, folderPriority)
                        val dbSubfolder = fsFolderRepository.findByUri(pathStr)
                        val dbSubfolderDTO = dbSubfolder?.let { FSFolderDTO().also { dto -> fsFolderMapper.updateFSFolderDTO(it, dto) } }

                        fsFolders.add(
                            SubfolderReviewItem(
                                name = childPath.fileName.toString(),
                                path = pathStr,
                                lastModified = Files.getLastModifiedTime(childPath).toInstant(),
                                expectedStatus = expectedStatus,
                                matchedPattern = matchedPattern,
                                existsInDb = dbSubfolder != null,
                                dbRecord = dbSubfolderDTO
                            )
                        )
                    }
                }
            }
        }

        // Get DB children for comparison
        val uriPrefix = if (folderUri.endsWith("/")) folderUri else "$folderUri/"
        val dbChildFiles = fsFileRepository.findImmediateChildFiles(uriPrefix)
            .map { FSFileDTO().also { dto -> fsFileMapper.updateFSFileDTO(it, dto) } }
        val dbChildFolders = fsFolderRepository.findImmediateChildFolders(uriPrefix)
            .map { FSFolderDTO().also { dto -> fsFolderMapper.updateFSFolderDTO(it, dto) } }

        // Build summary
        val fsFilePaths = fsFiles.map { it.path }.toSet()
        val dbFilePaths = dbChildFiles.mapNotNull { it.uri }.toSet()
        val fsFolderPaths = fsFolders.map { it.path }.toSet()
        val dbFolderPaths = dbChildFolders.mapNotNull { it.uri }.toSet()

        val byExpectedStatus = fsFiles.groupBy { it.expectedStatus }
            .mapValues { it.value.size }

        val summary = FolderReviewSummary(
            fsFileCount = fsFiles.size,
            dbFileCount = dbChildFiles.size,
            matchingFileCount = (fsFilePaths intersect dbFilePaths).size,
            fsOnlyFileCount = (fsFilePaths - dbFilePaths).size,
            dbOnlyFileCount = (dbFilePaths - fsFilePaths).size,
            fsFolderCount = fsFolders.size,
            dbFolderCount = dbChildFolders.size,
            matchingFolderCount = (fsFolderPaths intersect dbFolderPaths).size,
            fsOnlyFolderCount = (fsFolderPaths - dbFolderPaths).size,
            dbOnlyFolderCount = (dbFolderPaths - fsFolderPaths).size,
            byExpectedStatus = byExpectedStatus
        )

        return FolderReviewResult(
            folder = folderDTO,
            filesOnFs = fsFiles.sortedBy { it.filename },
            filesInDb = dbChildFiles,
            foldersOnFs = fsFolders.sortedBy { it.name },
            foldersInDb = dbChildFolders,
            summary = summary
        )
    }

    override fun findMatchingPattern(path: String, patterns: List<String>): String? {
        for (pattern in patterns) {
            try {
                if (Pattern.compile(pattern).matcher(path).matches()) {
                    return pattern
                }
            } catch (e: Exception) {
                log.warn("Invalid regex pattern '{}': {}", pattern, e.message)
            }
        }
        return null
    }

    /**
     * Load paths from a text file, one per line.
     * Ignores empty lines and lines starting with #.
     */
    private fun loadPathsFromFile(path: Path): List<String> {
        return Files.readAllLines(path)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /**
     * Check if a path is within any of the start paths.
     */
    private fun isWithinStartPaths(path: String, startPaths: List<String>): Boolean {
        if (startPaths.isEmpty()) return true
        return startPaths.any { startPath ->
            path == startPath || path.startsWith(if (startPath.endsWith("/")) startPath else "$startPath/")
        }
    }

    /**
     * Extract file extension from a path.
     */
    private fun extractExtension(path: String): String {
        val filename = path.substringAfterLast("/")
        val dotIndex = filename.lastIndexOf('.')
        return if (dotIndex > 0) filename.substring(dotIndex + 1).lowercase() else ""
    }

    /**
     * Build folder PatternSet from CrawlConfigDTO.
     */
    private fun buildFolderPatterns(config: CrawlConfigDTO): PatternSet {
        return PatternSet(
            skip = parsePatterns(config.folderPatternsSkip) + crawlConfiguration.defaults.folderPatterns.skip,
            locate = parsePatterns(config.folderPatternsLocate),
            index = parsePatterns(config.folderPatternsIndex),
            analyze = parsePatterns(config.folderPatternsAnalyze),
            semantic = parsePatterns(config.folderPatternsSemantic)
        )
    }

    /**
     * Build file PatternSet from CrawlConfigDTO.
     */
    private fun buildFilePatterns(config: CrawlConfigDTO): PatternSet {
        return PatternSet(
            skip = parsePatterns(config.filePatternsSkip) + crawlConfiguration.defaults.filePatterns.skip,
            locate = parsePatterns(config.filePatternsLocate),
            index = parsePatterns(config.filePatternsIndex),
            analyze = parsePatterns(config.filePatternsAnalyze),
            semantic = parsePatterns(config.filePatternsSemantic)
        )
    }

    /**
     * Parse pattern string (newline or comma separated) into list.
     */
    private fun parsePatterns(patterns: String?): List<String> {
        if (patterns.isNullOrBlank()) return emptyList()
        return patterns.split(Regex("[,\\n]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Find the first matching pattern for a file path.
     */
    private fun findMatchingPatternForFile(path: String, patterns: PatternSet, priority: PatternPriority): String? {
        for (status in priority.orderedStatuses()) {
            findMatchingPattern(path, patternsForStatus(patterns, status))?.let { return it }
        }
        return null
    }

    /**
     * Find the first matching pattern for a folder path.
     */
    private fun findMatchingPatternForFolder(path: String, patterns: PatternSet, priority: PatternPriority): String? {
        for (status in priority.orderedStatuses()) {
            findMatchingPattern(path, patternsForStatus(patterns, status))?.let { return it }
        }
        return null
    }

    private fun patternsForStatus(patterns: PatternSet, status: AnalysisStatus): List<String> = when (status) {
        AnalysisStatus.SKIP -> patterns.skip
        AnalysisStatus.LOCATE -> patterns.locate
        AnalysisStatus.INDEX -> patterns.index
        AnalysisStatus.ANALYZE -> patterns.analyze
        AnalysisStatus.SEMANTIC -> patterns.semantic
    }
}
