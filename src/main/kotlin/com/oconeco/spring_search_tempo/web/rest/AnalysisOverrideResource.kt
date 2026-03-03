package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.security.Principal
import java.time.OffsetDateTime

/**
 * REST controller for manual analysis status override.
 *
 * Allows administrators to manually set the analysisStatus of files and folders,
 * bypassing the automatic pattern-based assignment. This is useful for:
 * - Promoting specific files to higher analysis levels
 * - Demoting files that shouldn't be analyzed
 * - Fixing misclassified items
 *
 * All changes are tracked with:
 * - analysisStatusSetBy = "MANUAL"
 * - analysisStatusReason = "MANUAL: {reason provided by user}"
 */
@RestController
@RequestMapping("/api/analysis")
class AnalysisOverrideResource(
    private val fileRepository: FSFileRepository,
    private val folderRepository: FSFolderRepository
) {
    companion object {
        private val log = LoggerFactory.getLogger(AnalysisOverrideResource::class.java)
    }

    /**
     * Override the analysis status of a single file.
     *
     * @param id File ID
     * @param request Override request with new status and reason
     * @param principal Current user for audit trail
     * @return Updated file info
     */
    @PostMapping("/files/{id}/override")
    @Transactional
    fun overrideFileStatus(
        @PathVariable id: Long,
        @RequestBody request: OverrideRequest,
        principal: Principal?
    ): ResponseEntity<OverrideResponse> {
        val file = fileRepository.findById(id).orElse(null)
            ?: return ResponseEntity.notFound().build()

        val oldStatus = file.analysisStatus
        val user = principal?.name ?: "anonymous"
        val reason = "MANUAL: ${request.reason ?: "User override"} (by $user)"

        file.analysisStatus = request.status
        file.analysisStatusReason = reason
        file.analysisStatusSetBy = "MANUAL"

        fileRepository.save(file)

        log.info(
            "File {} analysis status changed {} -> {} by {}",
            file.uri, oldStatus, request.status, user
        )

        return ResponseEntity.ok(
            OverrideResponse(
                id = file.id!!,
                uri = file.uri!!,
                entityType = "FILE",
                oldStatus = oldStatus,
                newStatus = request.status,
                reason = reason,
                updatedBy = user,
                updatedAt = OffsetDateTime.now()
            )
        )
    }

    /**
     * Override the analysis status of a single folder.
     *
     * @param id Folder ID
     * @param request Override request with new status and reason
     * @param principal Current user for audit trail
     * @return Updated folder info
     */
    @PostMapping("/folders/{id}/override")
    @Transactional
    fun overrideFolderStatus(
        @PathVariable id: Long,
        @RequestBody request: OverrideRequest,
        principal: Principal?
    ): ResponseEntity<OverrideResponse> {
        val folder = folderRepository.findById(id).orElse(null)
            ?: return ResponseEntity.notFound().build()

        val oldStatus = folder.analysisStatus
        val user = principal?.name ?: "anonymous"
        val reason = "MANUAL: ${request.reason ?: "User override"} (by $user)"

        folder.analysisStatus = request.status
        folder.analysisStatusReason = reason
        folder.analysisStatusSetBy = "MANUAL"

        folderRepository.save(folder)

        log.info(
            "Folder {} analysis status changed {} -> {} by {}",
            folder.uri, oldStatus, request.status, user
        )

        return ResponseEntity.ok(
            OverrideResponse(
                id = folder.id!!,
                uri = folder.uri!!,
                entityType = "FOLDER",
                oldStatus = oldStatus,
                newStatus = request.status,
                reason = reason,
                updatedBy = user,
                updatedAt = OffsetDateTime.now()
            )
        )
    }

    /**
     * Bulk override analysis status for multiple files.
     *
     * @param request Bulk override request with file IDs and new status
     * @param principal Current user for audit trail
     * @return List of override results
     */
    @PostMapping("/files/bulk-override")
    @Transactional
    fun bulkOverrideFileStatus(
        @RequestBody request: BulkOverrideRequest,
        principal: Principal?
    ): ResponseEntity<BulkOverrideResponse> {
        val user = principal?.name ?: "anonymous"
        val reason = "MANUAL: ${request.reason ?: "Bulk override"} (by $user)"
        val results = mutableListOf<OverrideResponse>()
        val errors = mutableListOf<String>()

        val files = fileRepository.findAllById(request.ids)
        val fileMap = files.associateBy { it.id }

        for (id in request.ids) {
            val file = fileMap[id]
            if (file == null) {
                errors.add("File $id not found")
                continue
            }

            val oldStatus = file.analysisStatus
            file.analysisStatus = request.status
            file.analysisStatusReason = reason
            file.analysisStatusSetBy = "MANUAL"

            results.add(
                OverrideResponse(
                    id = file.id!!,
                    uri = file.uri!!,
                    entityType = "FILE",
                    oldStatus = oldStatus,
                    newStatus = request.status,
                    reason = reason,
                    updatedBy = user,
                    updatedAt = OffsetDateTime.now()
                )
            )
        }

        fileRepository.saveAll(files)

        log.info(
            "Bulk file override: {} files updated to {} by {}",
            results.size, request.status, user
        )

        return ResponseEntity.ok(
            BulkOverrideResponse(
                updated = results,
                errors = errors,
                totalRequested = request.ids.size,
                totalUpdated = results.size
            )
        )
    }

    /**
     * Bulk override analysis status for multiple folders.
     *
     * @param request Bulk override request with folder IDs and new status
     * @param principal Current user for audit trail
     * @return List of override results
     */
    @PostMapping("/folders/bulk-override")
    @Transactional
    fun bulkOverrideFolderStatus(
        @RequestBody request: BulkOverrideRequest,
        principal: Principal?
    ): ResponseEntity<BulkOverrideResponse> {
        val user = principal?.name ?: "anonymous"
        val reason = "MANUAL: ${request.reason ?: "Bulk override"} (by $user)"
        val results = mutableListOf<OverrideResponse>()
        val errors = mutableListOf<String>()

        val folders = folderRepository.findAllById(request.ids)
        val folderMap = folders.associateBy { it.id }

        for (id in request.ids) {
            val folder = folderMap[id]
            if (folder == null) {
                errors.add("Folder $id not found")
                continue
            }

            val oldStatus = folder.analysisStatus
            folder.analysisStatus = request.status
            folder.analysisStatusReason = reason
            folder.analysisStatusSetBy = "MANUAL"

            results.add(
                OverrideResponse(
                    id = folder.id!!,
                    uri = folder.uri!!,
                    entityType = "FOLDER",
                    oldStatus = oldStatus,
                    newStatus = request.status,
                    reason = reason,
                    updatedBy = user,
                    updatedAt = OffsetDateTime.now()
                )
            )
        }

        folderRepository.saveAll(folders)

        log.info(
            "Bulk folder override: {} folders updated to {} by {}",
            results.size, request.status, user
        )

        return ResponseEntity.ok(
            BulkOverrideResponse(
                updated = results,
                errors = errors,
                totalRequested = request.ids.size,
                totalUpdated = results.size
            )
        )
    }

    /**
     * Get available analysis status values.
     */
    @GetMapping("/statuses")
    fun getAnalysisStatuses(): ResponseEntity<List<AnalysisStatusInfo>> {
        return ResponseEntity.ok(
            AnalysisStatus.entries.map { status ->
                AnalysisStatusInfo(
                    value = status.name,
                    description = getStatusDescription(status)
                )
            }
        )
    }

    private fun getStatusDescription(status: AnalysisStatus): String {
        return when (status) {
            AnalysisStatus.SKIP -> "Skip - Metadata only, no text extraction or analysis"
            AnalysisStatus.LOCATE -> "Locate - Metadata indexed for path-based search"
            AnalysisStatus.INDEX -> "Index - Full text extraction and FTS indexing"
            AnalysisStatus.ANALYZE -> "Analyze - Index + NLP (entities, sentiment)"
            AnalysisStatus.SEMANTIC -> "Semantic - Analyze + vector embeddings for semantic search"
        }
    }
}

/**
 * Request to override analysis status.
 */
data class OverrideRequest(
    /** New analysis status */
    val status: AnalysisStatus,
    /** Optional reason for the override */
    val reason: String? = null
)

/**
 * Request for bulk override.
 */
data class BulkOverrideRequest(
    /** IDs to override */
    val ids: List<Long>,
    /** New analysis status */
    val status: AnalysisStatus,
    /** Optional reason for the override */
    val reason: String? = null
)

/**
 * Response for a single override operation.
 */
data class OverrideResponse(
    val id: Long,
    val uri: String,
    val entityType: String,
    val oldStatus: AnalysisStatus?,
    val newStatus: AnalysisStatus,
    val reason: String,
    val updatedBy: String,
    val updatedAt: OffsetDateTime
)

/**
 * Response for bulk override operation.
 */
data class BulkOverrideResponse(
    val updated: List<OverrideResponse>,
    val errors: List<String>,
    val totalRequested: Int,
    val totalUpdated: Int
)

/**
 * Info about an analysis status value.
 */
data class AnalysisStatusInfo(
    val value: String,
    val description: String
)
