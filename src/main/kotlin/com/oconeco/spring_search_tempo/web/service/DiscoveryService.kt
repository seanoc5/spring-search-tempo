package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.domain.*
import com.oconeco.spring_search_tempo.base.repos.DiscoveredFolderRepository
import com.oconeco.spring_search_tempo.base.repos.DiscoverySessionRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Paths

@Service
class DiscoveryService(
    private val sessionRepository: DiscoverySessionRepository,
    private val folderRepository: DiscoveredFolderRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Process an uploaded discovery from a remote crawler.
     */
    @Transactional
    fun uploadDiscovery(request: DiscoveryUploadRequest): DiscoveryUploadResponse {
        log.info("Processing discovery upload from host {} with {} folders",
            request.host, request.folders.size)

        // Create session
        val session = DiscoverySession().apply {
            host = request.host
            osType = request.osType
            rootPaths = request.rootPaths.joinToString(",")
            status = DiscoveryStatus.PENDING
            totalFolders = request.folders.size
            discoveryDurationMs = request.discoveryDurationMs
        }

        sessionRepository.save(session)

        // Create folder entities
        var skipSuggested = 0
        var locateSuggested = 0
        var indexSuggested = 0

        val folders = request.folders.map { dto ->
            val suggestedStatus = dto.suggestedStatus?.let {
                try { SuggestedStatus.valueOf(it) } catch (e: Exception) { null }
            }

            when (suggestedStatus) {
                SuggestedStatus.SKIP -> skipSuggested++
                SuggestedStatus.LOCATE -> locateSuggested++
                SuggestedStatus.INDEX -> indexSuggested++
                else -> {}
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

        log.info("Created discovery session {} with {} folders (suggested: {} skip, {} locate, {} index)",
            session.id, folders.size, skipSuggested, locateSuggested, indexSuggested)

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

        return DiscoveryStatusResponse(
            sessionId = session.id!!,
            host = session.host ?: "",
            status = session.status.name,
            totalFolders = session.totalFolders,
            classifiedFolders = session.classifiedFolders,
            skipCount = session.skipCount,
            locateCount = session.locateCount,
            indexCount = session.indexCount,
            analyzeCount = session.analyzeCount
        )
    }

    /**
     * Get session with folder tree for classification UI.
     */
    @Transactional(readOnly = true)
    fun getSessionForClassification(sessionId: Long): DiscoverySessionDTO {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { NotFoundException("Discovery session $sessionId not found") }

        val folders = folderRepository.findBySessionIdOrderByPath(sessionId)

        return DiscoverySessionDTO(
            id = session.id!!,
            host = session.host ?: "",
            osType = session.osType ?: "",
            rootPaths = session.rootPaths?.split(",") ?: emptyList(),
            status = session.status.name,
            totalFolders = session.totalFolders,
            classifiedFolders = session.classifiedFolders,
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
        val pathPrefix = if (folderPath.endsWith("/") || folderPath.endsWith("\\")) {
            folderPath
        } else {
            "$folderPath%"
        }
        val updated = folderRepository.updateAssignedStatusByPathPrefix(sessionId, pathPrefix, status)
        if (updated > 0) {
            updateSessionCounts(sessionId)
        }
        log.info("Classified {} folders under {} as {}", updated, folderPath, status)
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

        updateSessionCounts(sessionId)

        return ApplySuggestionsResponse(
            sessionId = sessionId,
            skipApplied = skipApplied,
            locateApplied = locateApplied,
            indexApplied = indexApplied,
            totalApplied = skipApplied + locateApplied + indexApplied
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
     * Get all sessions for a host.
     */
    fun getSessionsForHost(host: String): List<DiscoverySessionSummaryDTO> {
        return sessionRepository.findByHostOrderByDateCreatedDesc(host)
            .map { toSummaryDTO(it) }
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

    private fun computeParentPath(path: String, osType: String): String? {
        return try {
            val p = Paths.get(path)
            p.parent?.toString()
        } catch (e: Exception) {
            // Fallback for paths that might not parse on this OS
            val separator = if (osType == "WINDOWS") "\\" else "/"
            val lastSep = path.lastIndexOf(separator)
            if (lastSep > 0) path.substring(0, lastSep) else null
        }
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
}

// ============ Request/Response DTOs ============

data class DiscoveryUploadRequest(
    val host: String,
    val folders: List<DiscoveredFolderUploadDTO>,
    val rootPaths: List<String>,
    val osType: String,
    val discoveryDurationMs: Long
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
    val analyzeCount: Int = 0
)

data class DiscoverySessionDTO(
    val id: Long,
    val host: String,
    val osType: String,
    val rootPaths: List<String>,
    val status: String,
    val totalFolders: Int,
    val classifiedFolders: Int,
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

data class ApplySuggestionsResponse(
    val sessionId: Long,
    val skipApplied: Int,
    val locateApplied: Int,
    val indexApplied: Int,
    val totalApplied: Int
)

data class ClassifyFolderRequest(
    val folderId: Long? = null,
    val folderPath: String? = null,
    val status: String,
    val includeSubtree: Boolean = false
)
