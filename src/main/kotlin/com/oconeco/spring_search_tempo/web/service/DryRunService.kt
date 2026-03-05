package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.DiscoveredFolder
import com.oconeco.spring_search_tempo.base.repos.DiscoveredFolderRepository
import com.oconeco.spring_search_tempo.base.repos.DiscoverySessionRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for generating dry run reports that show how folders would be
 * classified during a crawl without actually executing the crawl.
 *
 * Supports two modes:
 * - Short: Only folders with explicit pattern matches (anchor points)
 * - Detailed: All folders with resolved status (explicit + inherited)
 */
@Service
class DryRunService(
    private val sessionRepository: DiscoverySessionRepository,
    private val folderRepository: DiscoveredFolderRepository,
    private val databaseCrawlConfigService: DatabaseCrawlConfigService,
    private val crawlConfigConverter: CrawlConfigConverter,
    private val runtimeCrawlConfigService: CrawlConfigService,
    private val patternMatchingService: PatternMatchingService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Generate a dry run report for a crawl config.
     *
     * @param configId The crawl config ID
     * @param detailed If true, return all folders with resolved status. If false, only explicit matches.
     * @param sessionId Optional discovery session ID. If not provided, uses most recent session for config.
     * @param statusFilter Optional status filter to only return folders with matching status
     * @param pathPrefix Optional path prefix to filter results
     * @param limit Maximum number of folders to return (default 10000)
     */
    @Transactional(readOnly = true)
    fun generateDryRun(
        configId: Long,
        detailed: Boolean = false,
        sessionId: Long? = null,
        statusFilter: AnalysisStatus? = null,
        pathPrefix: String? = null,
        limit: Int = 10000
    ): DryRunResponse {
        val startTime = System.currentTimeMillis()

        // Load crawl config
        val config = databaseCrawlConfigService.get(configId)
        val definition = crawlConfigConverter.toDefinition(config)
        val effectivePatterns = runtimeCrawlConfigService.getEffectivePatterns(definition)

        // Find discovery session
        val session = if (sessionId != null) {
            sessionRepository.findById(sessionId)
                .orElseThrow { NotFoundException("Discovery session $sessionId not found") }
        } else {
            // Find most recent session linked to this config
            val sessions = sessionRepository.findByCrawlConfigIdOrderByAppliedAtDesc(configId)
            sessions.firstOrNull()
                ?: throw NotFoundException("No discovery session found for crawl config $configId")
        }

        // Load all folders from session
        val allFolders = folderRepository.findBySessionIdOrderByPath(session.id!!)
        log.info("Dry run for config {} using session {}: {} folders to process",
            configId, session.id, allFolders.size)

        // Build parent path -> status map for inheritance resolution
        val pathToStatus = mutableMapOf<String, ResolvedFolderStatus>()

        // Sort by depth to ensure parents are processed before children
        val sortedFolders = allFolders.sortedBy { it.depth }

        // Resolve status for each folder
        for (folder in sortedFolders) {
            val path = folder.path ?: continue
            val parentPath = folder.parentPath

            // Get parent status for inheritance
            val parentStatus = if (parentPath != null) {
                pathToStatus[parentPath]?.status
            } else null

            // Determine explicit match vs inherited
            val resolved = resolveStatus(
                path = path,
                folderPatterns = effectivePatterns.folderPatterns,
                parentStatus = parentStatus
            )

            pathToStatus[path] = resolved
        }

        // Build summary counts
        val summary = DryRunSummary(
            skip = pathToStatus.values.count { it.status == AnalysisStatus.SKIP },
            locate = pathToStatus.values.count { it.status == AnalysisStatus.LOCATE },
            index = pathToStatus.values.count { it.status == AnalysisStatus.INDEX },
            analyze = pathToStatus.values.count { it.status == AnalysisStatus.ANALYZE },
            semantic = pathToStatus.values.count { it.status == AnalysisStatus.SEMANTIC },
            explicitCount = pathToStatus.values.count { it.explicit },
            inheritedCount = pathToStatus.values.count { !it.explicit }
        )

        // Filter and limit folders for response
        var resultFolders = pathToStatus.entries.asSequence()

        // Apply path prefix filter
        if (!pathPrefix.isNullOrBlank()) {
            val normalizedPrefix = pathPrefix.trim()
            resultFolders = resultFolders.filter { (path, _) ->
                path.startsWith(normalizedPrefix, ignoreCase = true)
            }
        }

        // Apply status filter
        if (statusFilter != null) {
            resultFolders = resultFolders.filter { (_, resolved) ->
                resolved.status == statusFilter
            }
        }

        // For short mode, only return explicit matches
        if (!detailed) {
            resultFolders = resultFolders.filter { (_, resolved) -> resolved.explicit }
        }

        // Build folder DTOs
        val folderDTOs = resultFolders
            .take(limit)
            .map { (path, resolved) ->
                DryRunFolder(
                    path = path,
                    status = resolved.status.name,
                    explicit = resolved.explicit,
                    matchedPattern = resolved.matchedPattern,
                    inheritedFrom = resolved.inheritedFrom
                )
            }
            .toList()

        val durationMs = System.currentTimeMillis() - startTime
        log.info("Dry run completed in {}ms: {} total, {} explicit, returning {} folders",
            durationMs, pathToStatus.size, summary.explicitCount, folderDTOs.size)

        return DryRunResponse(
            configId = configId,
            configName = config.name ?: "UNNAMED",
            sessionId = session.id!!,
            host = session.host ?: "",
            detailed = detailed,
            summary = summary,
            folders = folderDTOs,
            totalFolders = pathToStatus.size,
            returnedFolders = folderDTOs.size,
            truncated = folderDTOs.size < pathToStatus.size,
            durationMs = durationMs
        )
    }

    /**
     * Resolve the analysis status for a folder, tracking whether it was
     * an explicit pattern match or inherited from parent.
     */
    private fun resolveStatus(
        path: String,
        folderPatterns: PatternSet,
        parentStatus: AnalysisStatus?
    ): ResolvedFolderStatus {
        // Check explicit patterns in priority order
        val skipMatch = findMatchingPattern(path, folderPatterns.skip)
        if (skipMatch != null) {
            return ResolvedFolderStatus(
                status = AnalysisStatus.SKIP,
                explicit = true,
                matchedPattern = skipMatch,
                inheritedFrom = null
            )
        }

        val semanticMatch = findMatchingPattern(path, folderPatterns.semantic)
        if (semanticMatch != null) {
            return ResolvedFolderStatus(
                status = AnalysisStatus.SEMANTIC,
                explicit = true,
                matchedPattern = semanticMatch,
                inheritedFrom = null
            )
        }

        val analyzeMatch = findMatchingPattern(path, folderPatterns.analyze)
        if (analyzeMatch != null) {
            return ResolvedFolderStatus(
                status = AnalysisStatus.ANALYZE,
                explicit = true,
                matchedPattern = analyzeMatch,
                inheritedFrom = null
            )
        }

        val indexMatch = findMatchingPattern(path, folderPatterns.index)
        if (indexMatch != null) {
            return ResolvedFolderStatus(
                status = AnalysisStatus.INDEX,
                explicit = true,
                matchedPattern = indexMatch,
                inheritedFrom = null
            )
        }

        val locateMatch = findMatchingPattern(path, folderPatterns.locate)
        if (locateMatch != null) {
            return ResolvedFolderStatus(
                status = AnalysisStatus.LOCATE,
                explicit = true,
                matchedPattern = locateMatch,
                inheritedFrom = null
            )
        }

        // No explicit match - inherit from parent
        val inherited = parentStatus ?: AnalysisStatus.LOCATE
        return ResolvedFolderStatus(
            status = inherited,
            explicit = false,
            matchedPattern = null,
            inheritedFrom = if (parentStatus != null) "parent" else "default"
        )
    }

    /**
     * Find the first matching pattern for a path, returning the pattern string.
     */
    private fun findMatchingPattern(path: String, patterns: List<String>): String? {
        for (pattern in patterns) {
            try {
                if (Regex(pattern).matches(path)) {
                    return pattern
                }
            } catch (e: Exception) {
                log.warn("Invalid regex pattern '{}': {}", pattern, e.message)
            }
        }
        return null
    }
}

/**
 * Internal class for tracking resolved folder status during processing.
 */
private data class ResolvedFolderStatus(
    val status: AnalysisStatus,
    val explicit: Boolean,
    val matchedPattern: String?,
    val inheritedFrom: String?
)

// ============ Response DTOs ============

data class DryRunResponse(
    val configId: Long,
    val configName: String,
    val sessionId: Long,
    val host: String,
    val detailed: Boolean,
    val summary: DryRunSummary,
    val folders: List<DryRunFolder>,
    val totalFolders: Int,
    val returnedFolders: Int,
    val truncated: Boolean,
    val durationMs: Long
)

data class DryRunSummary(
    val skip: Int,
    val locate: Int,
    val index: Int,
    val analyze: Int,
    val semantic: Int,
    val explicitCount: Int,
    val inheritedCount: Int
)

data class DryRunFolder(
    val path: String,
    val status: String,
    val explicit: Boolean,
    val matchedPattern: String?,
    val inheritedFrom: String?
)
