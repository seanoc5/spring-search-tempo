package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.domain.*
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.DiscoveredFolderRepository
import com.oconeco.spring_search_tempo.base.repos.DiscoverySessionRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Paths
import java.time.OffsetDateTime

@Service
class DiscoveryService(
    private val sessionRepository: DiscoverySessionRepository,
    private val folderRepository: DiscoveredFolderRepository,
    private val crawlConfigRepository: CrawlConfigRepository,
    private val crawlConfigService: DatabaseCrawlConfigService,
    private val crawlConfigConverter: CrawlConfigConverter,
    private val discoveryTemplateClassifier: DiscoveryTemplateClassifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Process an uploaded discovery from a remote crawler.
     */
    @Transactional
    fun uploadDiscovery(request: DiscoveryUploadRequest): DiscoveryUploadResponse {
        val normalizedHost = request.host.trim()
        require(normalizedHost.isNotBlank()) { "host is required" }

        log.info(
            "Processing discovery upload from host {} with {} folders (createNewSession={})",
            normalizedHost,
            request.folders.size,
            request.createNewSession
        )

        if (request.createNewSession) {
            val archivedCount = archiveExistingSessionsForHost(normalizedHost)
            if (archivedCount > 0) {
                log.info("Archived {} previous discovery session(s) for host {}", archivedCount, normalizedHost)
            }
        }

        val templatePlan = discoveryTemplateClassifier.buildPlan(
            osType = request.osType,
            rootPaths = request.rootPaths,
            folders = request.folders.map { TemplateFolderInput(it.path, it.name, it.depth) }
        )

        // Create session
        val session = DiscoverySession().apply {
            host = normalizedHost
            osType = request.osType
            rootPaths = request.rootPaths.joinToString(",")
            status = DiscoveryStatus.PENDING
            totalFolders = request.folders.size
            discoveryDurationMs = request.discoveryDurationMs
        }

        sessionRepository.save(session)

        // Create folder entities
        val folders = request.folders.map { dto ->
            val suggestedStatus = templatePlan.statusByPath[dto.path] ?: dto.suggestedStatus?.let {
                try {
                    SuggestedStatus.valueOf(it.uppercase())
                } catch (_: Exception) {
                    null
                }
            }

            DiscoveredFolder().apply {
                this.session = session
                this.path = dto.path
                this.name = dto.name
                this.depth = dto.depth
                this.folderCount = dto.folderCount
                this.fileCount = dto.fileCount
                this.totalSize = dto.totalSize
                this.isHidden = dto.isHidden
                this.suggestedStatus = suggestedStatus
                this.parentPath = computeParentPath(dto.path, request.osType)
            }
        }

        folderRepository.saveAll(folders)

        val skipSuggested = templatePlan.counts[SuggestedStatus.SKIP] ?: 0
        val locateSuggested = templatePlan.counts[SuggestedStatus.LOCATE] ?: 0
        val indexSuggested = templatePlan.counts[SuggestedStatus.INDEX] ?: 0
        val analyzeSuggested = templatePlan.counts[SuggestedStatus.ANALYZE] ?: 0
        log.info(
            "Created discovery session {} with {} folders (profile={} {}%, suggested: {} skip, {} locate, {} index, {} analyze)",
            session.id,
            folders.size,
            templatePlan.profile,
            templatePlan.confidencePercent,
            skipSuggested,
            locateSuggested,
            indexSuggested,
            analyzeSuggested
        )

        val baseUrl = "/discovery/${session.id}/classify"

        return DiscoveryUploadResponse(
            sessionId = session.id!!,
            host = request.host,
            foldersReceived = folders.size,
            classifyUrl = baseUrl,
            status = "PENDING"
        )
    }

    /**
     * Get discovery session status.
     */
    fun getStatus(sessionId: Long): DiscoveryStatusResponse {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { NotFoundException("Discovery session $sessionId not found") }
        val semanticCount = folderRepository.countBySessionIdAndAssignedStatus(sessionId, AnalysisStatus.SEMANTIC).toInt()

        return DiscoveryStatusResponse(
            sessionId = session.id!!,
            host = session.host ?: "",
            status = session.status.name,
            totalFolders = session.totalFolders,
            classifiedFolders = session.classifiedFolders,
            skipCount = session.skipCount,
            locateCount = session.locateCount,
            indexCount = session.indexCount,
            analyzeCount = session.analyzeCount,
            semanticCount = semanticCount
        )
    }

    /**
     * Get session with folder tree for classification UI.
     */
    @Transactional(readOnly = true)
    fun getSessionForClassification(sessionId: Long, maxDepth: Int = 3): DiscoverySessionDTO {
        val startedAt = System.nanoTime()
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { NotFoundException("Discovery session $sessionId not found") }

        val folders = folderRepository.findBySessionIdAndMaxDepth(sessionId, maxDepth)
        val loadedAt = System.nanoTime()
        val templatePlan = discoveryTemplateClassifier.buildPlan(
            osType = session.osType ?: "",
            rootPaths = session.rootPaths?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            folders = folders.map {
                TemplateFolderInput(
                    path = it.path ?: "",
                    name = it.name ?: "",
                    depth = it.depth
                )
            }
        )
        val plannedAt = System.nanoTime()
        log.debug(
            "Classification page load session {} maxDepth={} loaded {} folders in {} ms, template plan in {} ms",
            sessionId,
            maxDepth,
            folders.size,
            (loadedAt - startedAt) / 1_000_000,
            (plannedAt - loadedAt) / 1_000_000
        )
        val semanticCount = folderRepository.countBySessionIdAndAssignedStatus(sessionId, AnalysisStatus.SEMANTIC).toInt()

        return DiscoverySessionDTO(
            id = session.id!!,
            host = session.host ?: "",
            osType = session.osType ?: "",
            rootPaths = session.rootPaths?.split(",") ?: emptyList(),
            status = session.status.name,
            totalFolders = session.totalFolders,
            classifiedFolders = session.classifiedFolders,
            skipCount = session.skipCount,
            locateCount = session.locateCount,
            indexCount = session.indexCount,
            analyzeCount = session.analyzeCount,
            semanticCount = semanticCount,
            suggestedProfile = templatePlan.profile.name,
            profileConfidencePercent = templatePlan.confidencePercent,
            profileReason = templatePlan.reason,
            folders = folders.map { toFolderDTO(it) }
        )
    }

    /**
     * Get folders at a specific depth for lazy loading.
     */
    fun getFoldersAtDepth(sessionId: Long, depth: Int): List<DiscoveredFolderDTO> {
        val folders = folderRepository.findBySessionIdAndDepth(sessionId, depth)
        return folders.map { toFolderDTO(it) }
    }

    /**
     * Get children of a specific folder.
     */
    fun getChildFolders(sessionId: Long, parentPath: String): List<DiscoveredFolderDTO> {
        val folders = folderRepository.findBySessionIdAndParentPath(sessionId, parentPath)
        return folders.map { toFolderDTO(it) }
    }

    /**
     * Get folders explicitly assigned a classification status.
     */
    @Transactional(readOnly = true)
    fun getAssignedFolders(
        sessionId: Long,
        status: AnalysisStatus,
        page: Int = 0,
        size: Int = 100,
        sortBy: String = "path",
        sortDir: String = "asc",
        pathFilter: String? = null
    ): AssignedFolderPageResponse {
        val normalizedFilter = pathFilter?.trim().orEmpty()
        val safeSort = when (sortBy.trim().lowercase()) {
            "path" -> "path"
            "name" -> "name"
            "depth" -> "depth"
            "foldercount" -> "folderCount"
            "filecount" -> "fileCount"
            "totalsize" -> "totalSize"
            else -> "path"
        }
        val direction = if (sortDir.equals("desc", ignoreCase = true)) {
            Sort.Direction.DESC
        } else {
            Sort.Direction.ASC
        }
        val pageable = PageRequest.of(
            page.coerceAtLeast(0),
            size.coerceIn(10, 500),
            Sort.by(direction, safeSort)
        )
        val resultPage = folderRepository.findBySessionIdAndAssignedStatusAndPathContainingIgnoreCase(
            sessionId = sessionId,
            status = status,
            path = normalizedFilter,
            pageable = pageable
        )
        return AssignedFolderPageResponse(
            status = status.name,
            totalCount = resultPage.totalElements,
            totalPages = resultPage.totalPages,
            pageNumber = resultPage.number,
            pageSize = resultPage.size,
            hasPrevious = resultPage.hasPrevious(),
            hasNext = resultPage.hasNext(),
            sortBy = safeSort,
            sortDir = direction.name.lowercase(),
            filter = normalizedFilter,
            folders = resultPage.content.map { toFolderDTO(it) }
        )
    }

    /**
     * Classify a single folder.
     */
    @Transactional
    fun classifyFolder(sessionId: Long, folderId: Long, status: AnalysisStatus): Int {
        val updated = folderRepository.updateAssignedStatus(folderId, status)
        if (updated > 0) {
            updateSessionCounts(sessionId)
        }
        return updated
    }

    /**
     * Classify a folder and all its descendants.
     */
    @Transactional
    fun classifySubtree(sessionId: Long, folderPath: String, status: AnalysisStatus): Int {
        val trimmedPath = folderPath.trim()
        val slashPrefix = if (trimmedPath.endsWith("/")) "$trimmedPath%" else "$trimmedPath/%"
        val backslashPrefix = if (trimmedPath.endsWith("\\")) "$trimmedPath%" else "$trimmedPath\\%"

        val updated = folderRepository.updateAssignedStatusForSubtreeUnassigned(
            sessionId = sessionId,
            folderPath = trimmedPath,
            slashPrefix = slashPrefix,
            backslashPrefix = backslashPrefix,
            status = status
        )
        if (updated > 0) {
            updateSessionCounts(sessionId)
        }
        log.info(
            "Classified subtree for {} as {} (updated root + descendants without explicit assignment): {} rows",
            folderPath,
            status,
            updated
        )
        return updated
    }

    /**
     * Apply all suggested statuses as assigned statuses.
     */
    @Transactional
    fun applySuggestedStatuses(sessionId: Long): ApplySuggestionsResponse {
        var skipApplied = folderRepository.applySuggestedStatus(sessionId, SuggestedStatus.SKIP, AnalysisStatus.SKIP)
        var locateApplied = folderRepository.applySuggestedStatus(sessionId, SuggestedStatus.LOCATE, AnalysisStatus.LOCATE)
        var indexApplied = folderRepository.applySuggestedStatus(sessionId, SuggestedStatus.INDEX, AnalysisStatus.INDEX)
        var analyzeApplied = folderRepository.applySuggestedStatus(sessionId, SuggestedStatus.ANALYZE, AnalysisStatus.ANALYZE)
        var semanticApplied = folderRepository.applySuggestedStatus(sessionId, SuggestedStatus.SEMANTIC, AnalysisStatus.SEMANTIC)

        updateSessionCounts(sessionId)

        return ApplySuggestionsResponse(
            sessionId = sessionId,
            skipApplied = skipApplied,
            locateApplied = locateApplied,
            indexApplied = indexApplied,
            analyzeApplied = analyzeApplied,
            semanticApplied = semanticApplied,
            totalApplied = skipApplied + locateApplied + indexApplied + analyzeApplied + semanticApplied
        )
    }

    /**
     * Rebuild suggested statuses for a session using a selected profile template.
     */
    @Transactional
    fun applySuggestedTemplate(sessionId: Long, profile: DiscoveryUserProfile): TemplateApplyResponse {
        val session = sessionRepository.findByIdWithFolders(sessionId)
            .orElseThrow { NotFoundException("Discovery session $sessionId not found") }

        val rootPaths = session.rootPaths
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val templatePlan = discoveryTemplateClassifier.buildPlan(
            osType = session.osType ?: "",
            rootPaths = rootPaths,
            folders = session.folders.map {
                TemplateFolderInput(
                    path = it.path ?: "",
                    name = it.name ?: "",
                    depth = it.depth
                )
            },
            forcedProfile = profile
        )

        session.folders.forEach { folder ->
            val path = folder.path ?: return@forEach
            val suggested = templatePlan.statusByPath[path] ?: SuggestedStatus.LOCATE
            folder.suggestedStatus = suggested
        }

        val skipSuggested = templatePlan.counts[SuggestedStatus.SKIP] ?: 0
        val locateSuggested = templatePlan.counts[SuggestedStatus.LOCATE] ?: 0
        val indexSuggested = templatePlan.counts[SuggestedStatus.INDEX] ?: 0
        val analyzeSuggested = templatePlan.counts[SuggestedStatus.ANALYZE] ?: 0
        val semanticSuggested = templatePlan.counts[SuggestedStatus.SEMANTIC] ?: 0

        return TemplateApplyResponse(
            sessionId = sessionId,
            profile = profile.name,
            skipSuggested = skipSuggested,
            locateSuggested = locateSuggested,
            indexSuggested = indexSuggested,
            analyzeSuggested = analyzeSuggested,
            semanticSuggested = semanticSuggested
        )
    }

    /**
     * Get all pending discovery sessions.
     */
    fun getPendingSessions(): List<DiscoverySessionSummaryDTO> {
        return sessionRepository.findByStatusOrderByDateCreatedDesc(DiscoveryStatus.PENDING)
            .map { toSummaryDTO(it) }
    }

    /**
     * Get all discovery sessions.
     */
    fun getAllSessions(): List<DiscoverySessionSummaryDTO> {
        return sessionRepository.findAll(Sort.by(Sort.Direction.DESC, "dateCreated"))
            .map { toSummaryDTO(it) }
    }

    /**
     * Get all sessions for a host.
     */
    fun getSessionsForHost(host: String): List<DiscoverySessionSummaryDTO> {
        return sessionRepository.findByHostOrderByDateCreatedDesc(host)
            .map { toSummaryDTO(it) }
    }

    /**
     * Find crawl configs that are candidates for updates for a discovered host.
     */
    fun getCrawlConfigCandidates(host: String): List<CrawlConfigCandidateDTO> {
        val normalized = host.trim()
        return crawlConfigService.findAll(null, Pageable.unpaged()).content
            .filter { cfg ->
                cfg.sourceHost?.trim()?.equals(normalized, ignoreCase = true) == true
            }
            .sortedBy { (it.label ?: it.name ?: "").lowercase() }
            .map {
                CrawlConfigCandidateDTO(
                    id = it.id!!,
                    name = it.name ?: "UNNAMED",
                    displayLabel = it.label ?: it.name ?: "Unnamed Crawl",
                    sourceHost = it.sourceHost
                )
            }
    }

    /**
     * Apply classified discovery folders to create or update a crawl config.
     */
    @Transactional
    fun applyToCrawlConfig(sessionId: Long, request: ApplyDiscoveryRequest): ApplyDiscoveryResult {
        val session = sessionRepository.findByIdWithFolders(sessionId)
            .orElseThrow { NotFoundException("Discovery session $sessionId not found") }

        val roots = session.rootPaths
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val statusToPaths = mutableMapOf<AnalysisStatus, MutableList<String>>()
        session.folders.forEach { folder ->
            val effective = effectiveStatus(folder) ?: return@forEach
            val rawPath = folder.path?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            statusToPaths.computeIfAbsent(effective) { mutableListOf() }.add(rawPath)
        }

        val skipPatterns = buildFolderPatterns(statusToPaths[AnalysisStatus.SKIP].orEmpty())
        val locatePatterns = buildFolderPatterns(statusToPaths[AnalysisStatus.LOCATE].orEmpty())
        val indexPatterns = buildFolderPatterns(statusToPaths[AnalysisStatus.INDEX].orEmpty())
        val analyzePatterns = buildFolderPatterns(statusToPaths[AnalysisStatus.ANALYZE].orEmpty())
        val semanticPatterns = buildFolderPatterns(statusToPaths[AnalysisStatus.SEMANTIC].orEmpty())

        val targetConfigId: Long
        val action: ApplyDiscoveryAction

        when (request.mode) {
            ApplyDiscoveryMode.UPDATE -> {
                val existingId = request.crawlConfigId
                    ?: throw IllegalArgumentException("crawlConfigId is required for UPDATE mode")

                val dto = crawlConfigService.get(existingId)
                val existingHost = dto.sourceHost?.trim()
                val sessionHost = session.host?.trim()
                if (!existingHost.isNullOrBlank() &&
                    !sessionHost.isNullOrBlank() &&
                    !existingHost.equals(sessionHost, ignoreCase = true)
                ) {
                    throw IllegalArgumentException(
                        "Config host mismatch: config host '$existingHost' does not match discovery host '$sessionHost'"
                    )
                }
                dto.sourceHost = session.host
                dto.startPaths = roots
                dto.folderPatternsSkip = crawlConfigConverter.toJsonArray(skipPatterns)
                dto.folderPatternsLocate = crawlConfigConverter.toJsonArray(locatePatterns)
                dto.folderPatternsIndex = crawlConfigConverter.toJsonArray(indexPatterns)
                dto.folderPatternsAnalyze = crawlConfigConverter.toJsonArray(analyzePatterns)
                dto.folderPatternsSemantic = crawlConfigConverter.toJsonArray(semanticPatterns)
                dto.enabled = request.enableConfig
                dto.description = discoveryBackfilledDescription(dto.description, session)
                crawlConfigService.update(existingId, dto)
                targetConfigId = existingId
                action = ApplyDiscoveryAction.UPDATED
            }
            ApplyDiscoveryMode.CREATE -> {
                val dto = CrawlConfigDTO().apply {
                    name = normalizedConfigName(request.newConfigName, session)
                    label = normalizedDisplayLabel(request.newDisplayLabel, session)
                    description = "Discovery-applied config for host ${session.host} (session ${session.id})"
                    status = Status.NEW
                    analysisStatus = AnalysisStatus.LOCATE
                    version = 0L
                    enabled = request.enableConfig
                    sourceHost = session.host
                    startPaths = roots
                    maxDepth = 20
                    followLinks = false
                    parallel = true
                    folderPatternsSkip = crawlConfigConverter.toJsonArray(skipPatterns)
                    folderPatternsLocate = crawlConfigConverter.toJsonArray(locatePatterns)
                    folderPatternsIndex = crawlConfigConverter.toJsonArray(indexPatterns)
                    folderPatternsAnalyze = crawlConfigConverter.toJsonArray(analyzePatterns)
                    folderPatternsSemantic = crawlConfigConverter.toJsonArray(semanticPatterns)
                }

                if (crawlConfigService.nameExists(dto.name!!, sourceHost = dto.sourceHost)) {
                    throw IllegalArgumentException(
                        "Crawl config name already exists for host '${dto.sourceHost}': ${dto.name}"
                    )
                }

                targetConfigId = crawlConfigService.create(dto)
                action = ApplyDiscoveryAction.CREATED
            }
        }

        session.crawlConfig = crawlConfigRepository.findById(targetConfigId).orElse(null)
        session.status = DiscoveryStatus.APPLIED
        session.appliedAt = OffsetDateTime.now()
        sessionRepository.save(session)

        return ApplyDiscoveryResult(
            sessionId = sessionId,
            crawlConfigId = targetConfigId,
            action = action,
            host = session.host ?: "",
            roots = roots,
            skipPatterns = skipPatterns.size,
            locatePatterns = locatePatterns.size,
            indexPatterns = indexPatterns.size,
            analyzePatterns = analyzePatterns.size,
            semanticPatterns = semanticPatterns.size
        )
    }

    private fun updateSessionCounts(sessionId: Long) {
        val session = sessionRepository.findById(sessionId).orElse(null) ?: return

        session.classifiedFolders = folderRepository.countBySessionIdAndClassified(sessionId, true).toInt()
        session.skipCount = folderRepository.countBySessionIdAndAssignedStatus(sessionId, AnalysisStatus.SKIP).toInt()
        session.locateCount = folderRepository.countBySessionIdAndAssignedStatus(sessionId, AnalysisStatus.LOCATE).toInt()
        session.indexCount = folderRepository.countBySessionIdAndAssignedStatus(sessionId, AnalysisStatus.INDEX).toInt()
        session.analyzeCount = folderRepository.countBySessionIdAndAssignedStatus(sessionId, AnalysisStatus.ANALYZE).toInt()

        if (session.status == DiscoveryStatus.PENDING && session.classifiedFolders > 0) {
            session.status = DiscoveryStatus.CLASSIFYING
        }

        sessionRepository.save(session)
    }

    private fun archiveExistingSessionsForHost(host: String): Int {
        val existing = sessionRepository.findByHostOrderByDateCreatedDesc(host)
        if (existing.isEmpty()) return 0
        var archived = 0
        existing.forEach { session ->
            if (session.status != DiscoveryStatus.ARCHIVED) {
                session.status = DiscoveryStatus.ARCHIVED
                archived++
            }
        }
        if (archived > 0) {
            sessionRepository.saveAll(existing)
        }
        return archived
    }

    private fun computeParentPath(path: String, osType: String): String? {
        val p = path.trim()
        if (p.isBlank()) return null

        // For cross-platform compatibility (e.g., processing Windows paths on Linux),
        // parse parent path using source OS semantics.
        if (osType.equals("WINDOWS", ignoreCase = true)) {
            // Drive root has no parent (e.g., C:\ or C:/). Prevent self-parent loops.
            if (Regex("^[A-Za-z]:[\\\\/]?$").matches(p)) return null

            val lastSep = p.lastIndexOfAny(charArrayOf('\\', '/'))
            if (lastSep <= 0) return null

            // For immediate children of drive root (e.g., C:\Users), keep root with trailing slash.
            if (lastSep == 2 && p.length > 3 && p[1] == ':') {
                return p.substring(0, 3) // "C:\"
            }
            return p.substring(0, lastSep)
        }

        val lastSep = p.lastIndexOf('/')
        if (lastSep <= 0) return null
        return p.substring(0, lastSep)
    }

    private fun toFolderDTO(folder: DiscoveredFolder): DiscoveredFolderDTO {
        return DiscoveredFolderDTO(
            id = folder.id!!,
            path = folder.path ?: "",
            name = folder.name ?: "",
            depth = folder.depth,
            folderCount = folder.folderCount,
            fileCount = folder.fileCount,
            totalSize = folder.totalSize,
            isHidden = folder.isHidden,
            suggestedStatus = folder.suggestedStatus?.name,
            assignedStatus = folder.assignedStatus?.name,
            classified = folder.classified,
            parentPath = folder.parentPath
        )
    }

    private fun toSummaryDTO(session: DiscoverySession): DiscoverySessionSummaryDTO {
        return DiscoverySessionSummaryDTO(
            id = session.id!!,
            host = session.host ?: "",
            osType = session.osType ?: "",
            status = session.status.name,
            totalFolders = session.totalFolders,
            classifiedFolders = session.classifiedFolders,
            dateCreated = session.dateCreated?.toString() ?: ""
        )
    }

    private fun effectiveStatus(folder: DiscoveredFolder): AnalysisStatus? {
        return folder.assignedStatus ?: when (folder.suggestedStatus) {
            SuggestedStatus.SKIP -> AnalysisStatus.SKIP
            SuggestedStatus.LOCATE -> AnalysisStatus.LOCATE
            SuggestedStatus.INDEX -> AnalysisStatus.INDEX
            SuggestedStatus.ANALYZE -> AnalysisStatus.ANALYZE
            SuggestedStatus.SEMANTIC -> AnalysisStatus.SEMANTIC
            else -> null
        }
    }

    private fun buildFolderPatterns(rawPaths: List<String>): List<String> {
        if (rawPaths.isEmpty()) return emptyList()

        val compressed = compressPaths(rawPaths.map { canonicalPath(it) })
        return compressed.map { canonical ->
            if (canonical == "/") {
                "^[\\\\/].*$"
            } else {
                val escaped = Regex.escape(canonical).replace("/", "[\\\\\\\\/]")
                "^$escaped([\\\\\\\\/].*)?$"
            }
        }
    }

    private fun compressPaths(paths: List<String>): List<String> {
        val sorted = paths.distinct().sortedWith(compareBy<String> { it.length }.thenBy { it })
        val selected = mutableListOf<String>()
        sorted.forEach { candidate ->
            if (selected.none { isAncestor(it, candidate) }) {
                selected.add(candidate)
            }
        }
        return selected
    }

    private fun isAncestor(ancestor: String, child: String): Boolean {
        if (ancestor == "/") return true
        if (ancestor == child) return true
        if (!child.startsWith(ancestor)) return false
        val next = child.getOrNull(ancestor.length) ?: return false
        return next == '/'
    }

    private fun canonicalPath(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        if (normalized == "/") return "/"
        return normalized.trimEnd('/').ifBlank { "/" }
    }

    private fun normalizedConfigName(input: String?, session: DiscoverySession): String {
        val candidate = input?.trim()?.takeIf { it.isNotBlank() }
            ?: "DISCOVERY_${session.host}_${session.id}"
        return candidate.uppercase().replace(Regex("[^A-Z0-9_]+"), "_")
    }

    private fun normalizedDisplayLabel(input: String?, session: DiscoverySession): String {
        return input?.trim()?.takeIf { it.isNotBlank() }
            ?: "Discovery ${session.host} (${session.id})"
    }

    private fun discoveryBackfilledDescription(existing: String?, session: DiscoverySession): String {
        val prefix = existing?.trim()?.takeIf { it.isNotBlank() }
            ?: "Discovery-applied config"
        return "$prefix | host=${session.host}, session=${session.id}"
    }
}

// ============ Request/Response DTOs ============

data class DiscoveryUploadRequest(
    val host: String,
    val folders: List<DiscoveredFolderUploadDTO>,
    val rootPaths: List<String>,
    val osType: String,
    val discoveryDurationMs: Long,
    val createNewSession: Boolean = false
)

data class DiscoveredFolderUploadDTO(
    val path: String,
    val name: String,
    val depth: Int,
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val totalSize: Long = 0,
    val isHidden: Boolean = false,
    val suggestedStatus: String? = null
)

data class DiscoveryUploadResponse(
    val sessionId: Long,
    val host: String,
    val foldersReceived: Int,
    val classifyUrl: String,
    val status: String
)

data class DiscoveryStatusResponse(
    val sessionId: Long,
    val host: String,
    val status: String,
    val totalFolders: Int,
    val classifiedFolders: Int,
    val skipCount: Int,
    val locateCount: Int,
    val indexCount: Int,
    val analyzeCount: Int = 0,
    val semanticCount: Int = 0
)

data class DiscoverySessionDTO(
    val id: Long,
    val host: String,
    val osType: String,
    val rootPaths: List<String>,
    val status: String,
    val totalFolders: Int,
    val classifiedFolders: Int,
    val skipCount: Int,
    val locateCount: Int,
    val indexCount: Int,
    val analyzeCount: Int = 0,
    val semanticCount: Int = 0,
    val suggestedProfile: String,
    val profileConfidencePercent: Int,
    val profileReason: String,
    val folders: List<DiscoveredFolderDTO>
)

data class DiscoveredFolderDTO(
    val id: Long,
    val path: String,
    val name: String,
    val depth: Int,
    val folderCount: Int,
    val fileCount: Int,
    val totalSize: Long,
    val isHidden: Boolean,
    val suggestedStatus: String?,
    val assignedStatus: String?,
    val classified: Boolean,
    val parentPath: String?
)

data class DiscoverySessionSummaryDTO(
    val id: Long,
    val host: String,
    val osType: String,
    val status: String,
    val totalFolders: Int,
    val classifiedFolders: Int,
    val dateCreated: String
)

enum class ApplyDiscoveryMode {
    CREATE,
    UPDATE
}

enum class ApplyDiscoveryAction {
    CREATED,
    UPDATED
}

data class ApplyDiscoveryRequest(
    val mode: ApplyDiscoveryMode,
    val crawlConfigId: Long? = null,
    val newConfigName: String? = null,
    val newDisplayLabel: String? = null,
    val enableConfig: Boolean = true
)

data class ApplyDiscoveryResult(
    val sessionId: Long,
    val crawlConfigId: Long,
    val action: ApplyDiscoveryAction,
    val host: String,
    val roots: List<String>,
    val skipPatterns: Int,
    val locatePatterns: Int,
    val indexPatterns: Int,
    val analyzePatterns: Int,
    val semanticPatterns: Int = 0
)

data class CrawlConfigCandidateDTO(
    val id: Long,
    val name: String,
    val displayLabel: String,
    val sourceHost: String?
)

data class ApplySuggestionsResponse(
    val sessionId: Long,
    val skipApplied: Int,
    val locateApplied: Int,
    val indexApplied: Int,
    val analyzeApplied: Int = 0,
    val semanticApplied: Int = 0,
    val totalApplied: Int
)

data class TemplateApplyResponse(
    val sessionId: Long,
    val profile: String,
    val skipSuggested: Int,
    val locateSuggested: Int,
    val indexSuggested: Int,
    val analyzeSuggested: Int,
    val semanticSuggested: Int = 0
)

data class AssignedFolderPageResponse(
    val status: String,
    val totalCount: Long,
    val totalPages: Int,
    val pageNumber: Int,
    val pageSize: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val sortBy: String,
    val sortDir: String,
    val filter: String,
    val folders: List<DiscoveredFolderDTO>
)

data class ClassifyFolderRequest(
    val folderId: Long? = null,
    val folderPath: String? = null,
    val status: String,
    val includeSubtree: Boolean = false
)
