package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.HostNameHolder
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.CrawlMode
import com.oconeco.spring_search_tempo.base.domain.CrawlTemperature
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.base.service.CrawlSchedulingService
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService as RuntimeCrawlConfigService
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class RemoteCrawlPlannerService(
    private val databaseCrawlConfigService: DatabaseCrawlConfigService,
    private val crawlConfigConverter: CrawlConfigConverter,
    private val runtimeCrawlConfigService: RuntimeCrawlConfigService,
    private val patternMatchingService: PatternMatchingService,
    private val crawlSchedulingService: CrawlSchedulingService
) {
    companion object {
        private val log = LoggerFactory.getLogger(RemoteCrawlPlannerService::class.java)
    }

    fun buildBootstrap(host: String): RemoteBootstrapResponse {
        val trimmedHost = host.trim()
        require(trimmedHost.isNotBlank()) { "host is required" }

        val defaults = runtimeCrawlConfigService.getDefaults()
        val allConfigs = databaseCrawlConfigService.findAll(null, Pageable.unpaged())
        val configs = allConfigs.content
            .filter { cfg ->
                cfg.sourceHost.isNullOrBlank() || cfg.sourceHost.equals(trimmedHost, ignoreCase = true)
            }
            .map { config ->
            val definition = crawlConfigConverter.toDefinition(config)
            val effectivePatterns = runtimeCrawlConfigService.getEffectivePatterns(definition)
            RemoteCrawlConfigAssignment(
                crawlConfigId = config.id!!,
                name = config.name ?: "UNNAMED",
                displayLabel = config.label ?: config.name ?: "Unnamed Crawl",
                description = config.description,
                sourceHost = config.sourceHost,
                startPaths = definition.startPaths,
                maxDepth = definition.getMaxDepth(defaults),
                followLinks = definition.getFollowLinks(defaults),
                parallel = definition.getParallel(defaults),
                crawlMode = config.crawlMode,
                discoveryKeeperMaxDepth = config.discoveryKeeperMaxDepth,
                discoverySkipMaxDepth = config.discoverySkipMaxDepth,
                discoveryFileSampleCap = config.discoveryFileSampleCap,
                folderPatterns = PatternPayload(
                    skip = effectivePatterns.folderPatterns.skip,
                    locate = effectivePatterns.folderPatterns.locate,
                    index = effectivePatterns.folderPatterns.index,
                    analyze = effectivePatterns.folderPatterns.analyze,
                    semantic = effectivePatterns.folderPatterns.semantic
                ),
                filePatterns = PatternPayload(
                    skip = effectivePatterns.filePatterns.skip,
                    locate = effectivePatterns.filePatterns.locate,
                    index = effectivePatterns.filePatterns.index,
                    analyze = effectivePatterns.filePatterns.analyze,
                    semantic = effectivePatterns.filePatterns.semantic
                ),
                folderPatternPriority = PatternPriorityPayload(
                    skip = effectivePatterns.folderPatternPriority.skip,
                    semantic = effectivePatterns.folderPatternPriority.semantic,
                    analyze = effectivePatterns.folderPatternPriority.analyze,
                    index = effectivePatterns.folderPatternPriority.index,
                    locate = effectivePatterns.folderPatternPriority.locate
                ),
                filePatternPriority = PatternPriorityPayload(
                    skip = effectivePatterns.filePatternPriority.skip,
                    semantic = effectivePatterns.filePatternPriority.semantic,
                    analyze = effectivePatterns.filePatternPriority.analyze,
                    index = effectivePatterns.filePatternPriority.index,
                    locate = effectivePatterns.filePatternPriority.locate
                )
            )
        }

        return RemoteBootstrapResponse(
            serverHost = HostNameHolder.currentHostName,
            requestedHost = trimmedHost,
            assignments = configs
        )
    }

    fun classify(request: RemoteClassifyRequest): RemoteClassifyResponse {
        val host = request.host.trim()
        require(host.isNotBlank()) { "host is required" }

        val config = databaseCrawlConfigService.get(request.crawlConfigId)

        val definition = crawlConfigConverter.toDefinition(config)
        val effectivePatterns = runtimeCrawlConfigService.getEffectivePatterns(definition)

        val folderResults = request.folders
            .filter { it.path.isNotBlank() }
            .map { item ->
                val status = patternMatchingService.determineFolderAnalysisStatus(
                    path = item.path,
                    patterns = effectivePatterns.folderPatterns,
                    parentStatus = item.parentStatus,
                    priority = effectivePatterns.folderPatternPriority
                )
                RemoteClassifiedPath(
                    path = item.path,
                    analysisStatus = status,
                    instructions = instructionsFor(status)
                )
            }

        val fileResults = request.files
            .filter { it.path.isNotBlank() }
            .map { item ->
                val status = patternMatchingService.determineFileAnalysisStatus(
                    path = item.path,
                    filePatterns = effectivePatterns.filePatterns,
                    parentFolderStatus = item.parentFolderStatus ?: AnalysisStatus.LOCATE,
                    priority = effectivePatterns.filePatternPriority
                )
                RemoteClassifiedPath(
                    path = item.path,
                    analysisStatus = status,
                    instructions = instructionsFor(status)
                )
            }

        return RemoteClassifyResponse(
            serverHost = HostNameHolder.currentHostName,
            requestedHost = host,
            crawlConfigId = request.crawlConfigId,
            folders = folderResults,
            files = fileResults
        )
    }

    fun instructionsFor(status: AnalysisStatus): RemoteProcessingInstructions {
        return when (status) {
            AnalysisStatus.SKIP -> RemoteProcessingInstructions(
                persistMetadata = true,
                extractText = false,
                runNlp = false,
                runEmbedding = false
            )
            AnalysisStatus.LOCATE -> RemoteProcessingInstructions(
                persistMetadata = true,
                extractText = false,
                runNlp = false,
                runEmbedding = false
            )
            AnalysisStatus.INDEX -> RemoteProcessingInstructions(
                persistMetadata = true,
                extractText = true,
                runNlp = false,
                runEmbedding = false
            )
            AnalysisStatus.ANALYZE -> RemoteProcessingInstructions(
                persistMetadata = true,
                extractText = true,
                runNlp = true,
                runEmbedding = false
            )
            AnalysisStatus.SEMANTIC -> RemoteProcessingInstructions(
                persistMetadata = true,
                extractText = true,
                runNlp = true,
                runEmbedding = true
            )
        }
    }

    /**
     * Build a smart bootstrap response with temperature-based folder prioritization.
     *
     * If the crawl config has smartCrawlEnabled=true, this returns a prioritized list
     * of folders based on their temperature (HOT, WARM, COLD).
     *
     * If smartCrawlEnabled=false, falls back to standard bootstrap behavior.
     *
     * @param host Remote host name
     * @param crawlConfigId ID of the crawl config to use for smart scheduling
     * @return SmartBootstrapResponse with standard config plus prioritized folders
     */
    fun buildSmartBootstrap(host: String, crawlConfigId: Long): SmartBootstrapResponse {
        val trimmedHost = host.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-")
        require(trimmedHost.isNotBlank()) { "host is required" }

        val standardBootstrap = buildBootstrap(host)
        val config = databaseCrawlConfigService.get(crawlConfigId)

        if (config.smartCrawlEnabled != true) {
            log.debug("Smart crawl not enabled for config {}, returning standard bootstrap", crawlConfigId)
            return SmartBootstrapResponse(
                standardBootstrap = standardBootstrap,
                smartCrawlEnabled = false,
                prioritizedFolders = null,
                temperatureSummary = null
            )
        }

        val hotThresholdDays = config.hotThresholdDays
            ?: CrawlSchedulingService.DEFAULT_HOT_THRESHOLD_DAYS
        val warmThresholdDays = config.warmThresholdDays
            ?: CrawlSchedulingService.DEFAULT_WARM_THRESHOLD_DAYS

        val dueFolders = crawlSchedulingService.getFoldersDueForCrawl(
            sourceHost = trimmedHost,
            hotThresholdDays = hotThresholdDays,
            warmThresholdDays = warmThresholdDays
        )

        val prioritizedFolders = dueFolders.map { folderPriority ->
            PrioritizedFolder(
                path = extractPathFromUri(folderPriority.uri),
                temperature = folderPriority.temperature,
                priority = folderPriority.priority,
                changeScore = folderPriority.changeScore,
                lastCrawledAt = folderPriority.lastCrawledAt,
                childModifiedAt = folderPriority.childModifiedAt
            )
        }

        val summary = TemperatureSummary(
            hotCount = dueFolders.count { it.temperature == CrawlTemperature.HOT },
            warmCount = dueFolders.count { it.temperature == CrawlTemperature.WARM },
            coldCount = dueFolders.count { it.temperature == CrawlTemperature.COLD },
            totalDue = dueFolders.size
        )

        log.info(
            "Smart bootstrap for host {}: {} folders due (HOT={}, WARM={}, COLD={})",
            trimmedHost, summary.totalDue, summary.hotCount, summary.warmCount, summary.coldCount
        )

        return SmartBootstrapResponse(
            standardBootstrap = standardBootstrap,
            smartCrawlEnabled = true,
            prioritizedFolders = prioritizedFolders,
            temperatureSummary = summary
        )
    }

    /**
     * Extract the path portion from a remote URI.
     * e.g., "remote://hostname/path/to/folder" -> "/path/to/folder"
     */
    private fun extractPathFromUri(uri: String): String {
        return if (uri.startsWith("remote://")) {
            val pathStart = uri.indexOf('/', "remote://".length)
            if (pathStart >= 0) uri.substring(pathStart) else "/"
        } else {
            uri
        }
    }
}

data class RemoteBootstrapResponse(
    val serverHost: String,
    val requestedHost: String,
    val assignments: List<RemoteCrawlConfigAssignment>
)

data class RemoteCrawlConfigAssignment(
    val crawlConfigId: Long,
    val name: String,
    val displayLabel: String,
    val description: String?,
    val sourceHost: String?,
    val startPaths: List<String>,
    val maxDepth: Int,
    val followLinks: Boolean,
    val parallel: Boolean,
    val crawlMode: CrawlMode = CrawlMode.ENFORCE,
    val discoveryKeeperMaxDepth: Int = 20,
    val discoverySkipMaxDepth: Int = 10,
    val discoveryFileSampleCap: Int = 50,
    val folderPatterns: PatternPayload,
    val filePatterns: PatternPayload,
    val folderPatternPriority: PatternPriorityPayload = PatternPriorityPayload(),
    val filePatternPriority: PatternPriorityPayload = PatternPriorityPayload()
)

data class PatternPayload(
    val skip: List<String>,
    val locate: List<String>,
    val index: List<String>,
    val analyze: List<String>,
    val semantic: List<String>
)

data class PatternPriorityPayload(
    val skip: Int = 500,
    val semantic: Int = 400,
    val analyze: Int = 300,
    val index: Int = 200,
    val locate: Int = 100
)

data class RemoteClassifyRequest(
    val host: String,
    val crawlConfigId: Long,
    val folders: List<RemoteFolderPath> = emptyList(),
    val files: List<RemoteFilePath> = emptyList()
)

data class RemoteFolderPath(
    val path: String,
    val parentStatus: AnalysisStatus? = null
)

data class RemoteFilePath(
    val path: String,
    val parentFolderStatus: AnalysisStatus? = AnalysisStatus.LOCATE
)

data class RemoteClassifyResponse(
    val serverHost: String,
    val requestedHost: String,
    val crawlConfigId: Long,
    val folders: List<RemoteClassifiedPath>,
    val files: List<RemoteClassifiedPath>
)

data class RemoteClassifiedPath(
    val path: String,
    val analysisStatus: AnalysisStatus,
    val instructions: RemoteProcessingInstructions
)

data class RemoteProcessingInstructions(
    val persistMetadata: Boolean,
    val extractText: Boolean,
    val runNlp: Boolean,
    val runEmbedding: Boolean
)

// ============ Smart Bootstrap DTOs ============

/**
 * Response for smart bootstrap with temperature-based folder prioritization.
 */
data class SmartBootstrapResponse(
    /** Standard bootstrap with config assignments */
    val standardBootstrap: RemoteBootstrapResponse,
    /** Whether smart crawl is enabled for the requested config */
    val smartCrawlEnabled: Boolean,
    /** Folders prioritized by temperature, null if smart crawl disabled */
    val prioritizedFolders: List<PrioritizedFolder>?,
    /** Summary of folder temperatures, null if smart crawl disabled */
    val temperatureSummary: TemperatureSummary?
)

/**
 * A folder with crawl priority based on temperature.
 */
data class PrioritizedFolder(
    /** Folder path (without remote:// prefix) */
    val path: String,
    /** Temperature tier: HOT, WARM, or COLD */
    val temperature: CrawlTemperature,
    /** Numeric priority (lower = higher priority) */
    val priority: Int,
    /** Rolling change score (0-100) */
    val changeScore: Int,
    /** When this folder was last crawled */
    val lastCrawledAt: OffsetDateTime?,
    /** Most recent child file modification */
    val childModifiedAt: OffsetDateTime?
)

/**
 * Summary of folder temperatures for a host.
 */
data class TemperatureSummary(
    /** Number of HOT folders due for crawl */
    val hotCount: Int,
    /** Number of WARM folders due for crawl */
    val warmCount: Int,
    /** Number of COLD folders due for crawl */
    val coldCount: Int,
    /** Total folders due for crawl */
    val totalDue: Int
)
