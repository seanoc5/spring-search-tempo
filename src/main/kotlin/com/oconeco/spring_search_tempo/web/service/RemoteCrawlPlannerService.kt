package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.HostNameHolder
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService as RuntimeCrawlConfigService
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

@Service
class RemoteCrawlPlannerService(
    private val databaseCrawlConfigService: DatabaseCrawlConfigService,
    private val crawlConfigConverter: CrawlConfigConverter,
    private val runtimeCrawlConfigService: RuntimeCrawlConfigService,
    private val patternMatchingService: PatternMatchingService
) {

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
