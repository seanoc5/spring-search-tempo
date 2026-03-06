package com.oconeco.spring_search_tempo.web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.PatternPriority
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.oconeco.spring_search_tempo.web.model.BaselineCaptureRequestDTO
import com.oconeco.spring_search_tempo.web.model.BaselineCaptureResultDTO
import com.oconeco.spring_search_tempo.web.model.BaselineManifestFileDTO
import com.oconeco.spring_search_tempo.web.model.BaselineSamplingPolicy
import com.oconeco.spring_search_tempo.web.model.FolderValidationDiffDTO
import com.oconeco.spring_search_tempo.web.model.PatternMatchTraceDTO
import com.oconeco.spring_search_tempo.web.model.ValidationDiffTotalsDTO
import com.oconeco.spring_search_tempo.web.model.ValidationDiffType
import com.oconeco.spring_search_tempo.web.model.ValidationFileDiffRowDTO
import com.oconeco.spring_search_tempo.web.model.ValidationFilterDTO
import com.oconeco.spring_search_tempo.web.model.ValidationFolderSummaryDTO
import com.oconeco.spring_search_tempo.web.model.ValidationPatternSource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CrawlConfigValidationServiceImpl(
    private val crawlConfigService: DatabaseCrawlConfigService,
    private val crawlConfigConverter: CrawlConfigConverter,
    private val folderRepository: FSFolderRepository,
    private val fileRepository: FSFileRepository,
    private val patternMatchingService: PatternMatchingService,
    private val objectMapper: ObjectMapper
) : CrawlConfigValidationService {

    @Transactional(readOnly = true)
    override fun getFolderSummaries(crawlConfigId: Long): List<ValidationFolderSummaryDTO> {
        val config = crawlConfigService.get(crawlConfigId)
        val definition = crawlConfigConverter.toDefinition(config)
        val startPathSet = (config.startPaths ?: emptyList())
            .map { canonicalFolderUri(it) }
            .toSet()

        val candidates = folderRepository.findAllByCrawlConfigId(crawlConfigId)
            .filter { folder ->
                !folder.baselineManifest.isNullOrBlank() ||
                    startPathSet.contains(canonicalFolderUri(folder.uri.orEmpty()))
            }

        return candidates.map { folder ->
            if (!folder.baselineManifest.isNullOrBlank()) {
                val diff = buildFolderDiff(crawlConfigId, folder, definition, ValidationFilterDTO(), includeRows = false)
                ValidationFolderSummaryDTO(
                    crawlConfigId = crawlConfigId,
                    folderId = folder.id ?: 0L,
                    folderPath = folder.uri.orEmpty(),
                    baselineCapturedAt = folder.baselineCapturedAt,
                    baselineSampleFiles = folder.baselineSampleFiles,
                    baselineTotalFiles = folder.baselineTotalFiles,
                    currentFileCount = diff.currentFileCount,
                    mismatchCount = diff.totals.totalRows - diff.totals.ok,
                    statusDriftCount = diff.totals.statusDrift,
                    missingInCurrentCount = diff.totals.missingInCurrent,
                    newInCurrentCount = diff.totals.newInCurrent
                )
            } else {
                val currentFileCount = fileRepository.countByCrawlConfigIdAndUriPrefix(
                    crawlConfigId,
                    normalizeFolderPrefix(folder.uri.orEmpty())
                ).toInt()
                ValidationFolderSummaryDTO(
                    crawlConfigId = crawlConfigId,
                    folderId = folder.id ?: 0L,
                    folderPath = folder.uri.orEmpty(),
                    baselineCapturedAt = null,
                    baselineSampleFiles = null,
                    baselineTotalFiles = null,
                    currentFileCount = currentFileCount,
                    mismatchCount = 0,
                    statusDriftCount = 0,
                    missingInCurrentCount = 0,
                    newInCurrentCount = 0
                )
            }
        }.sortedBy { it.folderPath.lowercase() }
    }

    @Transactional(readOnly = true)
    override fun getFolderDiff(
        crawlConfigId: Long,
        folderId: Long,
        filter: ValidationFilterDTO
    ): FolderValidationDiffDTO {
        val folder = folderRepository.findById(folderId).orElseThrow { NotFoundException() }
        val config = crawlConfigService.get(crawlConfigId)
        val definition = crawlConfigConverter.toDefinition(config)
        return buildFolderDiff(crawlConfigId, folder, definition, filter, includeRows = true)
    }

    @Transactional
    override fun captureFolderBaseline(
        crawlConfigId: Long,
        folderId: Long,
        request: BaselineCaptureRequestDTO
    ): BaselineCaptureResultDTO {
        val folder = folderRepository.findById(folderId).orElseThrow { NotFoundException() }
        val normalizedMaxSamples = request.maxSamples.coerceIn(1, 500)
        val policy = request.samplingPolicy
        val seed = request.seed?.trim()?.ifBlank { null } ?: defaultSeed(crawlConfigId, folderId)
        val folderPrefix = normalizeFolderPrefix(folder.uri.orEmpty())

        val currentFiles = fileRepository.findByCrawlConfigIdAndUriPrefix(crawlConfigId, folderPrefix)
        val sampledFiles = sampleFiles(currentFiles, normalizedMaxSamples, policy, seed)
        val capturedAt = OffsetDateTime.now()
        val manifestFiles = sampledFiles.map { file ->
            BaselineManifestFileDTO(
                relPath = toRelativePath(folder.uri.orEmpty(), file.uri.orEmpty()),
                name = file.uri?.substringAfterLast('/') ?: "",
                ext = extensionOfPath(file.uri.orEmpty()),
                size = file.size,
                mtime = file.fsLastModified,
                hidden = file.uri?.substringAfterLast('/')?.startsWith('.') == true
            )
        }

        val manifestPayload = mapOf(
            "schemaVersion" to 1,
            "capturedAt" to capturedAt.toString(),
            "policy" to policy.name,
            "seed" to seed,
            "truncated" to (currentFiles.size > sampledFiles.size),
            "totalFileCount" to currentFiles.size,
            "sampleCount" to manifestFiles.size,
            "files" to manifestFiles
        )

        folder.baselineManifest = objectMapper.writeValueAsString(manifestPayload)
        folder.baselineCapturedAt = capturedAt
        folder.baselineSourceJobRunId = folder.jobRunId
        folder.baselineTotalFiles = currentFiles.size
        folder.baselineSampleFiles = manifestFiles.size
        folder.baselineSamplingPolicy = policy.name
        folder.baselineSeed = seed
        folder.baselineVersion = 1
        folderRepository.save(folder)

        return BaselineCaptureResultDTO(
            crawlConfigId = crawlConfigId,
            folderId = folder.id ?: 0L,
            folderPath = folder.uri.orEmpty(),
            capturedAt = capturedAt,
            totalFileCount = currentFiles.size,
            sampleFileCount = manifestFiles.size,
            samplingPolicy = policy,
            seed = seed,
            truncated = currentFiles.size > sampledFiles.size
        )
    }

    @Transactional
    override fun clearFolderBaseline(crawlConfigId: Long, folderId: Long): Boolean {
        val folder = folderRepository.findById(folderId).orElseThrow { NotFoundException() }
        folder.baselineManifest = null
        folder.baselineCapturedAt = null
        folder.baselineSourceJobRunId = null
        folder.baselineTotalFiles = null
        folder.baselineSampleFiles = null
        folder.baselineSamplingPolicy = null
        folder.baselineSeed = null
        folder.baselineVersion = 1
        folderRepository.save(folder)
        return true
    }

    @Transactional(readOnly = true)
    override fun recomputeFolderDiff(
        crawlConfigId: Long,
        folderId: Long,
        filter: ValidationFilterDTO
    ): FolderValidationDiffDTO = getFolderDiff(crawlConfigId, folderId, filter)

    private fun buildFolderDiff(
        crawlConfigId: Long,
        folder: FSFolder,
        definition: com.oconeco.spring_search_tempo.base.config.CrawlDefinition,
        filter: ValidationFilterDTO,
        includeRows: Boolean
    ): FolderValidationDiffDTO {
        val folderUri = folder.uri.orEmpty()
        val folderPrefix = normalizeFolderPrefix(folderUri)
        val currentFiles = fileRepository.findByCrawlConfigIdAndUriPrefix(crawlConfigId, folderPrefix)
        val hasBaseline = !folder.baselineManifest.isNullOrBlank()
        val baselineFiles = if (hasBaseline) parseBaselineFiles(folder.baselineManifest.orEmpty()) else emptyList()

        val allRows = if (hasBaseline) {
            computeDiffRows(folderUri, folder.analysisStatus, definition, baselineFiles, currentFiles)
        } else {
            emptyList()
        }
        val rows = if (includeRows) allRows.filter { shouldIncludeRow(it, filter) } else emptyList()

        val totals = if (hasBaseline) {
            computeTotals(allRows)
        } else {
            ValidationDiffTotalsDTO(
                totalRows = 0,
                ok = 0,
                statusDrift = 0,
                assignmentMismatch = 0,
                metadataDrift = 0,
                missingInCurrent = 0,
                newInCurrent = 0
            )
        }

        return FolderValidationDiffDTO(
            crawlConfigId = crawlConfigId,
            folderId = folder.id ?: 0L,
            folderPath = folderUri,
            baselineCapturedAt = folder.baselineCapturedAt,
            baselineTotalFiles = folder.baselineTotalFiles,
            baselineSampleFiles = folder.baselineSampleFiles,
            currentFileCount = currentFiles.size,
            filter = filter,
            totals = totals,
            rows = rows
        )
    }

    private fun computeDiffRows(
        folderUri: String,
        folderStatus: AnalysisStatus?,
        definition: com.oconeco.spring_search_tempo.base.config.CrawlDefinition,
        baselineFiles: List<BaselineManifestFileDTO>,
        currentFiles: List<FSFile>
    ): List<ValidationFileDiffRowDTO> {
        val parentStatus = folderStatus ?: AnalysisStatus.LOCATE
        val baselineByPath = baselineFiles.associateBy { it.relPath }
        val currentByPath = currentFiles.associateBy { toRelativePath(folderUri, it.uri.orEmpty()) }
        val allPaths = (baselineByPath.keys + currentByPath.keys).toSortedSet()

        return allPaths.map { relPath ->
            val baseline = baselineByPath[relPath]
            val current = currentByPath[relPath]
            val fullPath = toFullPath(folderUri, relPath)
            val baselineTrace = baseline?.let {
                evaluateFileTrace(
                    fullPath,
                    definition.filePatterns,
                    parentStatus,
                    definition.filePatternPriority ?: PatternPriority()
                )
            }
            val currentTrace = current?.let {
                evaluateFileTrace(
                    fullPath,
                    definition.filePatterns,
                    parentStatus,
                    definition.filePatternPriority ?: PatternPriority()
                )
            }

            val diffType = classifyDiff(
                inBaseline = baseline != null,
                inCurrent = current != null,
                baselinePredicted = baselineTrace?.effectiveStatus,
                currentPredicted = currentTrace?.effectiveStatus,
                dbAssigned = current?.analysisStatus,
                baselineSize = baseline?.size,
                currentSize = current?.size,
                baselineMtime = baseline?.mtime,
                currentMtime = current?.fsLastModified
            )

            ValidationFileDiffRowDTO(
                path = relPath,
                name = relPath.substringAfterLast('/'),
                inBaseline = baseline != null,
                inCurrent = current != null,
                baselineSize = baseline?.size,
                currentSize = current?.size,
                baselineMtime = baseline?.mtime,
                currentMtime = current?.fsLastModified,
                dbAssignedStatus = current?.analysisStatus,
                baselinePredictedStatus = baselineTrace?.effectiveStatus,
                currentPredictedStatus = currentTrace?.effectiveStatus,
                baselineTrace = baselineTrace,
                currentTrace = currentTrace,
                diffType = diffType
            )
        }
    }

    private fun classifyDiff(
        inBaseline: Boolean,
        inCurrent: Boolean,
        baselinePredicted: AnalysisStatus?,
        currentPredicted: AnalysisStatus?,
        dbAssigned: AnalysisStatus?,
        baselineSize: Long?,
        currentSize: Long?,
        baselineMtime: OffsetDateTime?,
        currentMtime: OffsetDateTime?
    ): ValidationDiffType {
        if (!inCurrent) return ValidationDiffType.MISSING_IN_CURRENT
        if (!inBaseline) return ValidationDiffType.NEW_IN_CURRENT
        if (baselinePredicted != currentPredicted) return ValidationDiffType.STATUS_DRIFT
        if (dbAssigned != null && currentPredicted != null && dbAssigned != currentPredicted) {
            return ValidationDiffType.ASSIGNMENT_MISMATCH
        }
        if (baselineSize != currentSize || baselineMtime != currentMtime) {
            return ValidationDiffType.METADATA_DRIFT
        }
        return ValidationDiffType.OK
    }

    private fun computeTotals(rows: List<ValidationFileDiffRowDTO>): ValidationDiffTotalsDTO {
        return ValidationDiffTotalsDTO(
            totalRows = rows.size,
            ok = rows.count { it.diffType == ValidationDiffType.OK },
            statusDrift = rows.count { it.diffType == ValidationDiffType.STATUS_DRIFT },
            assignmentMismatch = rows.count { it.diffType == ValidationDiffType.ASSIGNMENT_MISMATCH },
            metadataDrift = rows.count { it.diffType == ValidationDiffType.METADATA_DRIFT },
            missingInCurrent = rows.count { it.diffType == ValidationDiffType.MISSING_IN_CURRENT },
            newInCurrent = rows.count { it.diffType == ValidationDiffType.NEW_IN_CURRENT }
        )
    }

    private fun shouldIncludeRow(row: ValidationFileDiffRowDTO, filter: ValidationFilterDTO): Boolean {
        if (filter.onlyMismatches && row.diffType == ValidationDiffType.OK) return false
        if (filter.onlyStatusDrift && row.diffType != ValidationDiffType.STATUS_DRIFT) return false
        if (filter.onlyMissingOrNew &&
            row.diffType != ValidationDiffType.MISSING_IN_CURRENT &&
            row.diffType != ValidationDiffType.NEW_IN_CURRENT
        ) return false
        if (filter.statusFilter != null &&
            row.currentPredictedStatus != filter.statusFilter &&
            row.baselinePredictedStatus != filter.statusFilter
        ) return false
        if (filter.patternSourceFilter != null &&
            row.currentTrace?.patternSource != filter.patternSourceFilter &&
            row.baselineTrace?.patternSource != filter.patternSourceFilter
        ) return false
        return true
    }

    private fun evaluateFileTrace(
        path: String,
        patterns: PatternSet,
        parentFolderStatus: AnalysisStatus,
        priority: PatternPriority
    ): PatternMatchTraceDTO {
        fun firstMatch(patternList: List<String>): String? =
            patternList.firstOrNull { pattern ->
                try {
                    Regex(pattern).matches(path)
                } catch (_: Exception) {
                    false
                }
            }

        // Keep explicit SKIP first so it cannot be bypassed by other statuses.
        firstMatch(patterns.skip)?.let { pattern ->
            return trace(AnalysisStatus.SKIP, pattern)
        }

        for (status in priority.orderedStatuses().filter { it != AnalysisStatus.SKIP }) {
            firstMatch(patternsForStatus(patterns, status))?.let { pattern ->
                return trace(status, pattern)
            }
        }

        val inherited = patternMatchingService.determineFileAnalysisStatus(path, patterns, parentFolderStatus, priority)
        return PatternMatchTraceDTO(
            effectiveStatus = inherited,
            matchedPattern = null,
            patternSource = ValidationPatternSource.INHERITED,
            reason = "Inherited from folder status"
        )
    }

    private fun patternsForStatus(patterns: PatternSet, status: AnalysisStatus): List<String> = when (status) {
        AnalysisStatus.SKIP -> patterns.skip
        AnalysisStatus.LOCATE -> patterns.locate
        AnalysisStatus.INDEX -> patterns.index
        AnalysisStatus.ANALYZE -> patterns.analyze
        AnalysisStatus.SEMANTIC -> patterns.semantic
    }

    private fun trace(status: AnalysisStatus, pattern: String): PatternMatchTraceDTO {
        val label = when (status) {
            AnalysisStatus.SKIP -> "SKIP"
            AnalysisStatus.LOCATE -> "LOCATE"
            AnalysisStatus.INDEX -> "INDEX"
            AnalysisStatus.ANALYZE -> "ANALYZE"
            AnalysisStatus.SEMANTIC -> "SEMANTIC"
        }
        return PatternMatchTraceDTO(
            effectiveStatus = status,
            matchedPattern = pattern,
            patternSource = ValidationPatternSource.CRAWL_CONFIG,
            reason = "Matched file $label pattern"
        )
    }

    private fun parseBaselineFiles(baselineManifest: String): List<BaselineManifestFileDTO> {
        return try {
            val root = objectMapper.readTree(baselineManifest)
            val filesNode = root.path("files")
            if (!filesNode.isArray) return emptyList()
            filesNode.mapNotNull { node -> parseBaselineFileNode(node) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseBaselineFileNode(node: JsonNode): BaselineManifestFileDTO? {
        val relPath = node.path("relPath").asText("").trim()
        if (relPath.isBlank()) return null
        val name = node.path("name").asText(relPath.substringAfterLast('/'))
        val ext = node.path("ext").asText(null)?.takeIf { it.isNotBlank() }
        val size = if (node.hasNonNull("size")) node.path("size").asLong() else null
        val mtime = node.path("mtime").asText(null)?.let {
            runCatching { OffsetDateTime.parse(it) }.getOrNull()
        }
        val hidden = node.path("hidden").asBoolean(false)
        return BaselineManifestFileDTO(
            relPath = relPath,
            name = name,
            ext = ext,
            size = size,
            mtime = mtime,
            hidden = hidden
        )
    }

    private fun sampleFiles(
        files: List<FSFile>,
        maxSamples: Int,
        policy: BaselineSamplingPolicy,
        seed: String
    ): List<FSFile> {
        if (files.size <= maxSamples) {
            return files.sortedBy { it.uri.orEmpty() }
        }

        return when (policy) {
            BaselineSamplingPolicy.FIRST_N -> files.sortedBy { it.uri.orEmpty() }.take(maxSamples)
            BaselineSamplingPolicy.HASH_STABLE -> files.sortedBy { stableHash(seed, it.uri.orEmpty()) }.take(maxSamples)
            BaselineSamplingPolicy.REPRESENTATIVE_50 -> representativeSample(files, maxSamples, seed)
        }
    }

    private fun representativeSample(files: List<FSFile>, maxSamples: Int, seed: String): List<FSFile> {
        val sorted = files.sortedBy { it.uri.orEmpty() }
        val groups = sorted.groupBy { extensionOfPath(it.uri.orEmpty()) ?: "(none)" }
            .toSortedMap()
            .mapValues { it.value.toMutableList() }
            .toMutableMap()

        val picks = mutableListOf<FSFile>()
        while (picks.size < maxSamples && groups.isNotEmpty()) {
            val emptyKeys = mutableListOf<String>()
            for ((ext, queue) in groups) {
                if (picks.size >= maxSamples) break
                if (queue.isNotEmpty()) {
                    picks.add(queue.removeAt(0))
                }
                if (queue.isEmpty()) {
                    emptyKeys.add(ext)
                }
            }
            emptyKeys.forEach { groups.remove(it) }
        }

        if (picks.size < maxSamples) {
            val existing = picks.mapNotNull { it.uri }.toHashSet()
            val remainder = sorted
                .filter { it.uri !in existing }
                .sortedBy { stableHash(seed, it.uri.orEmpty()) }
                .take(maxSamples - picks.size)
            picks.addAll(remainder)
        }

        return picks.take(maxSamples)
    }

    private fun stableHash(seed: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$seed::$value".toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    private fun defaultSeed(crawlConfigId: Long, folderId: Long): String = "cfg-$crawlConfigId-folder-$folderId-v1"

    private fun canonicalFolderUri(path: String): String {
        if (path.isBlank()) return "/"
        val trimmed = path.trim()
        if (trimmed == "/") return "/"
        return trimmed.trimEnd('/')
    }

    private fun normalizeFolderPrefix(folderUri: String): String {
        if (folderUri == "/") return "/"
        return if (folderUri.endsWith("/")) folderUri else "$folderUri/"
    }

    private fun toRelativePath(folderUri: String, fileUri: String): String {
        val prefix = normalizeFolderPrefix(folderUri)
        return if (fileUri.startsWith(prefix)) {
            fileUri.removePrefix(prefix)
        } else {
            fileUri.substringAfterLast('/')
        }
    }

    private fun toFullPath(folderUri: String, relPath: String): String {
        return if (folderUri == "/") "/$relPath".replace("//", "/")
        else normalizeFolderPrefix(folderUri) + relPath
    }

    private fun extensionOfPath(path: String): String? {
        val fileName = path.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "")
        return ext.lowercase().takeIf { it.isNotBlank() && fileName.contains('.') }
    }
}
