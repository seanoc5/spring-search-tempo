package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.domain.*
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.CrawlDiscoveryFileSampleRepository
import com.oconeco.spring_search_tempo.base.repos.CrawlDiscoveryFolderObservationRepository
import com.oconeco.spring_search_tempo.base.repos.CrawlDiscoveryRunRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.base.service.CrawlSchedulingService
import com.oconeco.spring_search_tempo.base.service.FileSampleAnalyzer
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.PatternStabilityInput
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

@Service
class CrawlDiscoveryObservationService(
    private val crawlConfigRepository: CrawlConfigRepository,
    private val crawlDiscoveryRunRepository: CrawlDiscoveryRunRepository,
    private val crawlDiscoveryFolderObservationRepository: CrawlDiscoveryFolderObservationRepository,
    private val crawlDiscoveryFileSampleRepository: CrawlDiscoveryFileSampleRepository,
    private val fsFolderRepository: FSFolderRepository,
    private val databaseCrawlConfigService: DatabaseCrawlConfigService,
    private val crawlConfigConverter: CrawlConfigConverter,
    private val runtimeCrawlConfigService: CrawlConfigService,
    private val patternMatchingService: PatternMatchingService,
    private val crawlSchedulingService: CrawlSchedulingService,
    private val fileSampleAnalyzer: FileSampleAnalyzer
) {
    companion object {
        private val log = LoggerFactory.getLogger(CrawlDiscoveryObservationService::class.java)
    }

    @Transactional
    fun startRun(crawlConfigId: Long, host: String, jobRunId: Long): CrawlDiscoveryRun {
        val existing = crawlDiscoveryRunRepository.findByJobRunId(jobRunId)
        if (existing != null) return existing

        val crawlConfig = crawlConfigRepository.findById(crawlConfigId)
            .orElseThrow { IllegalArgumentException("crawlConfig not found: $crawlConfigId") }

        val run = CrawlDiscoveryRun().apply {
            this.crawlConfig = crawlConfig
            this.jobRunId = jobRunId
            this.host = host
            this.runStatus = RunStatus.RUNNING
        }
        return crawlDiscoveryRunRepository.save(run)
    }

    @Transactional
    fun completeRun(jobRunId: Long, runStatus: RunStatus) {
        val run = crawlDiscoveryRunRepository.findByJobRunId(jobRunId) ?: return
        run.runStatus = runStatus
        run.completedAt = OffsetDateTime.now()
        crawlDiscoveryRunRepository.save(run)
    }

    @Transactional
    fun ingest(
        crawlConfigId: Long,
        host: String,
        jobRunId: Long,
        folders: List<RemoteDiscoveryFolderObsIngestItem>,
        fileSamples: List<RemoteDiscoveryFileSampleIngestItem>,
        sampleCap: Int
    ) {
        if (folders.isEmpty() && fileSamples.isEmpty()) return

        val now = OffsetDateTime.now()
        val effectiveCap = sampleCap.coerceIn(1, 50)

        val dedupFolders = folders
            .filter { it.path.isNotBlank() }
            .associateBy { normalizePath(it.path) }
        val paths = dedupFolders.keys.toList()
        val existingByPath = if (paths.isEmpty()) {
            emptyMap()
        } else {
            crawlDiscoveryFolderObservationRepository
                .findByCrawlConfigIdAndHostAndPathIn(crawlConfigId, host, paths)
                .associateBy { it.path!! }
        }

        val crawlConfig = crawlConfigRepository.findById(crawlConfigId)
            .orElseThrow { IllegalArgumentException("crawlConfig not found: $crawlConfigId") }

        val toSave = dedupFolders.map { (normalizedPath, item) ->
            val existing = existingByPath[normalizedPath]
            (existing ?: CrawlDiscoveryFolderObservation().apply {
                this.crawlConfig = crawlConfig
                this.host = host
                this.path = normalizedPath
            }).apply {
                this.depth = item.depth
                this.inSkipBranch = item.inSkipBranch
                this.lastSeenAt = now
                this.lastSeenJobRunId = jobRunId
            }
        }
        val persisted = if (toSave.isEmpty()) emptyList() else crawlDiscoveryFolderObservationRepository.saveAll(toSave)
        val persistedByPath = persisted.associateBy { it.path!! }

        val groupedSamples = fileSamples
            .filter { it.folderPath.isNotBlank() && it.fileName.isNotBlank() }
            .groupBy { normalizePath(it.folderPath) }

        // Track observations that received samples for folder type analysis
        val observationsWithNewSamples = mutableListOf<CrawlDiscoveryFolderObservation>()

        for ((path, observation) in persistedByPath) {
            val obsId = observation.id ?: continue
            crawlDiscoveryFileSampleRepository.deleteByFolderObservationId(obsId)

            val samplesForPath = groupedSamples[path].orEmpty()
                .sortedBy { it.sampleSlot }
                .take(effectiveCap)

            if (samplesForPath.isEmpty()) continue

            val entities = samplesForPath.mapIndexed { index, sample ->
                CrawlDiscoveryFileSample().apply {
                    folderObservation = observation
                    sampleSlot = (index + 1).coerceAtMost(50)
                    fileName = sample.fileName
                    fileSize = sample.fileSize
                    lastSeenAt = now
                }
            }
            val savedSamples = crawlDiscoveryFileSampleRepository.saveAll(entities)

            // Analyze file samples to detect folder type
            val analysisResult = fileSampleAnalyzer.analyzeFolder(savedSamples, path)
            observation.detectedFolderType = analysisResult.detectedType
            observation.detectionConfidence = analysisResult.confidence
            observationsWithNewSamples.add(observation)
        }

        // Save observations with updated folder type analysis
        if (observationsWithNewSamples.isNotEmpty()) {
            crawlDiscoveryFolderObservationRepository.saveAll(observationsWithNewSamples)
            log.debug("Analyzed folder types for {} observations", observationsWithNewSamples.size)
        }

        val run = crawlDiscoveryRunRepository.findByJobRunId(jobRunId)
        if (run != null) {
            run.observedFolderCount += persisted.size
            crawlDiscoveryRunRepository.save(run)
        }
    }

    @Transactional
    fun reapplySkipRules(crawlConfigId: Long, host: String, jobRunId: Long? = null): ReapplySkipResult {
        val normalizedHost = normalizeHost(host)
        val effectiveSkipPatterns = effectiveSkipPatterns(crawlConfigId)
        val observations = crawlDiscoveryFolderObservationRepository.findByCrawlConfigIdAndHost(crawlConfigId, normalizedHost)

        var changed = 0
        observations.forEach { observation ->
            val path = observation.path ?: return@forEach
            val overridden = resolvedSkipByCurrentRules(path, effectiveSkipPatterns, observation.manualOverride)
            if (observation.skipByCurrentRules != overridden) {
                observation.skipByCurrentRules = overridden
                changed++
            }
        }

        if (observations.isNotEmpty()) {
            crawlDiscoveryFolderObservationRepository.saveAll(observations)
        }

        val run = jobRunId?.let { crawlDiscoveryRunRepository.findByJobRunId(it) }
        if (run != null) {
            run.reapplyChangedCount = changed
            run.reapplyCompletedAt = OffsetDateTime.now()
            crawlDiscoveryRunRepository.save(run)
        }

        // Integration 1 & 3: Update pattern stability and trigger auto-cooling when patterns stabilize
        updatePatternStabilityAndCoolFolders(crawlConfigId, normalizedHost, changed, observations.size)

        return ReapplySkipResult(total = observations.size, changed = changed)
    }

    /**
     * Update pattern stability scores and trigger auto-cooling for stable folders.
     *
     * This is called after reapplySkipRules to feed discovery observation data
     * into the smart crawl temperature system.
     *
     * Integration 1: Calculate stability from recent runs and update FSFolder.patternStabilityScore
     * Integration 3: When patterns are very stable, trigger cooling (HOT→WARM→COLD)
     */
    private fun updatePatternStabilityAndCoolFolders(
        crawlConfigId: Long,
        host: String,
        changedThisRun: Int,
        totalThisRun: Int
    ) {
        // Get recent runs to calculate stability
        val recentRuns = crawlDiscoveryRunRepository
            .findTop10ByCrawlConfigIdAndHostOrderByStartedAtDesc(crawlConfigId, host)
            .filter { it.runStatus == RunStatus.COMPLETED }
            .take(5)

        if (recentRuns.size < 3) {
            log.debug("Not enough discovery runs ({}) to calculate pattern stability for config {} host {}",
                recentRuns.size, crawlConfigId, host)
            return
        }

        // Calculate stability score from recent runs
        val stabilityInputs = recentRuns.map {
            PatternStabilityInput(
                observedFolderCount = it.observedFolderCount,
                reapplyChangedCount = it.reapplyChangedCount
            )
        }
        val stabilityScore = crawlSchedulingService.calculatePatternStabilityScore(stabilityInputs)
            ?: return

        // Update stability score for all folders on this host
        val updated = crawlSchedulingService.updatePatternStabilityForHost(host, stabilityScore)
        log.info("Updated pattern stability to {} for {} folders on host {} (config {})",
            stabilityScore, updated, host, crawlConfigId)

        // Integration 3: If patterns are very stable, trigger auto-cooling
        if (stabilityScore >= 80 && changedThisRun == 0) {
            triggerAutoCoolingForStableFolders(host, stabilityScore)
        }
    }

    /**
     * Auto-cool folders when patterns are very stable.
     *
     * When discovery observations show patterns are stable (no changes for several runs),
     * we can safely cool folders to reduce crawl frequency:
     * - HOT folders that haven't changed in 3+ days → WARM
     * - WARM folders that haven't changed in 7+ days → COLD
     */
    private fun triggerAutoCoolingForStableFolders(host: String, stabilityScore: Int) {
        val folders = fsFolderRepository.findCrawlableFoldersBySourceHost(host)
        if (folders.isEmpty()) return

        val now = OffsetDateTime.now()
        var cooledHotToWarm = 0
        var cooledWarmToCold = 0

        for (folder in folders) {
            val lastCrawled = folder.lastCrawledAt ?: continue
            val daysSinceLastCrawl = ChronoUnit.DAYS.between(lastCrawled, now)

            when (folder.crawlTemperature) {
                CrawlTemperature.HOT -> {
                    // HOT folders can cool to WARM if stable and not crawled in 3+ days
                    if (daysSinceLastCrawl >= 3) {
                        folder.crawlTemperature = CrawlTemperature.WARM
                        folder.patternStabilityScore = stabilityScore
                        cooledHotToWarm++
                    }
                }
                CrawlTemperature.WARM -> {
                    // WARM folders can cool to COLD if very stable and not crawled in 7+ days
                    if (stabilityScore >= 90 && daysSinceLastCrawl >= 7) {
                        folder.crawlTemperature = CrawlTemperature.COLD
                        folder.patternStabilityScore = stabilityScore
                        cooledWarmToCold++
                    }
                }
                CrawlTemperature.COLD -> {
                    // Already cold, just update stability score
                    folder.patternStabilityScore = stabilityScore
                }
            }
        }

        if (cooledHotToWarm > 0 || cooledWarmToCold > 0) {
            fsFolderRepository.saveAll(folders)
            log.info("Auto-cooling for host {}: {} HOT→WARM, {} WARM→COLD (stability={})",
                host, cooledHotToWarm, cooledWarmToCold, stabilityScore)
        }
    }

    fun shouldSuggestEnforce(
        crawlConfigId: Long,
        host: String,
        minRuns: Int = 5,
        maxChangedRatio: Double = 0.02
    ): Boolean {
        val normalizedHost = normalizeHost(host)
        val recentRuns = crawlDiscoveryRunRepository
            .findTop10ByCrawlConfigIdAndHostOrderByStartedAtDesc(crawlConfigId, normalizedHost)
            .filter { it.runStatus == RunStatus.COMPLETED }
            .take(minRuns)

        if (recentRuns.size < minRuns) return false

        return recentRuns.all { run ->
            if (run.observedFolderCount <= 0) {
                false
            } else {
                (run.reapplyChangedCount.toDouble() / run.observedFolderCount.toDouble()) <= maxChangedRatio
            }
        }
    }

    @Transactional(readOnly = true)
    fun listObservations(
        crawlConfigId: Long,
        host: String,
        pathPrefix: String? = null,
        includeSamples: Boolean = true,
        page: Int = 0,
        limit: Int = 500
    ): DiscoveryObservationListResponse {
        val normalizedHost = normalizeHost(host)
        val normalizedPrefix = pathPrefix?.trim()?.takeIf { it.isNotBlank() }?.let { normalizePath(it) }
        val effectivePage = page.coerceAtLeast(0)
        val effectiveLimit = limit.coerceIn(1, 5000)
        val pageable = PageRequest.of(effectivePage, effectiveLimit, Sort.by(Sort.Direction.ASC, "path"))

        val observationPage = if (normalizedPrefix == null) {
            crawlDiscoveryFolderObservationRepository.findByCrawlConfigIdAndHost(
                crawlConfigId = crawlConfigId,
                host = normalizedHost,
                pageable = pageable
            )
        } else {
            crawlDiscoveryFolderObservationRepository.findByCrawlConfigIdAndHostAndPathStartingWith(
                crawlConfigId = crawlConfigId,
                host = normalizedHost,
                pathPrefix = normalizedPrefix,
                pageable = pageable
            )
        }
        val observations = observationPage.content

        val ids = observations.mapNotNull { it.id }
        val sampleByObservationId = if (!includeSamples || ids.isEmpty()) {
            emptyMap()
        } else {
            crawlDiscoveryFileSampleRepository
                .findByFolderObservationIdInOrderByFolderObservationIdAscSampleSlotAsc(ids)
                .groupBy { it.folderObservation?.id }
        }

        val rows = observations.map { obs ->
            val samples = sampleByObservationId[obs.id].orEmpty().map { sample ->
                DiscoveryFileSampleDTO(
                    sampleSlot = sample.sampleSlot,
                    fileName = sample.fileName ?: "",
                    fileSize = sample.fileSize,
                    lastSeenAt = sample.lastSeenAt
                )
            }
            toObservationDTO(obs, samples)
        }

        return DiscoveryObservationListResponse(
            crawlConfigId = crawlConfigId,
            host = normalizedHost,
            count = rows.size,
            totalCount = observationPage.totalElements,
            page = observationPage.number,
            pageSize = observationPage.size,
            totalPages = observationPage.totalPages.coerceAtLeast(1),
            hasNext = observationPage.hasNext(),
            hasPrevious = observationPage.hasPrevious(),
            suggestEnforce = shouldSuggestEnforce(crawlConfigId, normalizedHost),
            observations = rows
        )
    }

    @Transactional
    fun updateManualOverride(
        crawlConfigId: Long,
        host: String,
        path: String,
        manualOverride: DiscoveryManualOverride?
    ): DiscoveryObservationDTO {
        val normalizedHost = normalizeHost(host)
        val normalizedPath = normalizePath(path)
        val observation = crawlDiscoveryFolderObservationRepository
            .findByCrawlConfigIdAndHostAndPath(crawlConfigId, normalizedHost, normalizedPath)
            ?: throw IllegalArgumentException("Discovery observation not found for path: $normalizedPath")

        val effectiveSkipPatterns = effectiveSkipPatterns(crawlConfigId)
        observation.manualOverride = manualOverride
        observation.skipByCurrentRules = resolvedSkipByCurrentRules(
            path = normalizedPath,
            effectiveSkipPatterns = effectiveSkipPatterns,
            manualOverride = manualOverride
        )

        val saved = crawlDiscoveryFolderObservationRepository.save(observation)
        val samples = saved.id?.let { id ->
            crawlDiscoveryFileSampleRepository
                .findByFolderObservationIdInOrderByFolderObservationIdAscSampleSlotAsc(listOf(id))
                .map { sample ->
                    DiscoveryFileSampleDTO(
                        sampleSlot = sample.sampleSlot,
                        fileName = sample.fileName ?: "",
                        fileSize = sample.fileSize,
                        lastSeenAt = sample.lastSeenAt
                    )
                }
        }.orEmpty()

        return toObservationDTO(saved, samples)
    }

    private fun toObservationDTO(
        observation: CrawlDiscoveryFolderObservation,
        samples: List<DiscoveryFileSampleDTO>
    ): DiscoveryObservationDTO {
        // Calculate suggested analysis status from folder type analysis
        val suggestedStatus = observation.detectedFolderType?.let { folderType ->
            fileSampleAnalyzer.suggestAnalysisStatus(
                folderType,
                observation.detectionConfidence ?: 0.0
            )
        }

        return DiscoveryObservationDTO(
            id = observation.id ?: -1L,
            path = observation.path ?: "",
            depth = observation.depth,
            inSkipBranch = observation.inSkipBranch,
            manualOverride = observation.manualOverride,
            skipByCurrentRules = observation.skipByCurrentRules,
            lastSeenAt = observation.lastSeenAt,
            lastSeenJobRunId = observation.lastSeenJobRunId,
            detectedFolderType = observation.detectedFolderType,
            detectionConfidence = observation.detectionConfidence,
            suggestedAnalysisStatus = suggestedStatus,
            fileSamples = samples
        )
    }

    private fun effectiveSkipPatterns(crawlConfigId: Long): List<String> {
        val config = databaseCrawlConfigService.get(crawlConfigId)
        val definition = crawlConfigConverter.toDefinition(config)
        return runtimeCrawlConfigService.getEffectivePatterns(definition).folderPatterns.skip
    }

    private fun resolvedSkipByCurrentRules(
        path: String,
        effectiveSkipPatterns: List<String>,
        manualOverride: DiscoveryManualOverride?
    ): Boolean {
        val matched = patternMatchingService.matchesSkipPatternOnly(path, effectiveSkipPatterns).isSkip
        return when (manualOverride) {
            DiscoveryManualOverride.FORCE_KEEP -> false
            DiscoveryManualOverride.FORCE_SKIP -> true
            null -> matched
        }
    }

    private fun normalizeHost(rawHost: String): String {
        val host = rawHost.trim().lowercase()
        require(host.isNotBlank()) { "host is required" }
        return host.replace(Regex("[^a-z0-9._-]"), "-")
    }

    private fun normalizePath(raw: String): String {
        var normalized = raw.trim().replace('\\', '/')
        if (normalized.isBlank()) return "/"
        normalized = normalized.replace(Regex("/{2,}"), "/")
        if (!normalized.startsWith("/")) normalized = "/$normalized"
        if (normalized.length > 1 && normalized.endsWith("/")) normalized = normalized.removeSuffix("/")
        return normalized
    }
}

data class ReapplySkipResult(
    val total: Int,
    val changed: Int
)

data class ReapplySkipRequest(
    val host: String,
    val crawlConfigId: Long,
    val jobRunId: Long? = null
)

data class DiscoveryManualOverrideRequest(
    val host: String,
    val crawlConfigId: Long,
    val path: String,
    val manualOverride: DiscoveryManualOverride? = null
)

data class DiscoveryFileSampleDTO(
    val sampleSlot: Int,
    val fileName: String,
    val fileSize: Long?,
    val lastSeenAt: OffsetDateTime?
)

data class DiscoveryObservationDTO(
    val id: Long,
    val path: String,
    val depth: Int,
    val inSkipBranch: Boolean,
    val manualOverride: DiscoveryManualOverride?,
    val skipByCurrentRules: Boolean,
    val lastSeenAt: OffsetDateTime?,
    val lastSeenJobRunId: Long?,
    /** Detected folder type from file sample analysis (e.g., SOURCE_CODE, MEDIA) */
    val detectedFolderType: DetectedFolderType?,
    /** Confidence level of the detected folder type (0.0 to 1.0) */
    val detectionConfidence: Double?,
    /** Suggested analysis status based on folder type (SKIP, LOCATE, INDEX, ANALYZE) */
    val suggestedAnalysisStatus: AnalysisStatus?,
    val fileSamples: List<DiscoveryFileSampleDTO>
)

data class DiscoveryObservationListResponse(
    val crawlConfigId: Long,
    val host: String,
    val count: Int,
    val totalCount: Long,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val suggestEnforce: Boolean,
    val observations: List<DiscoveryObservationDTO>
)
