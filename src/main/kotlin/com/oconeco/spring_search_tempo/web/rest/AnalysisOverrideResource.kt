package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.service.AnalysisOverrideBatchResult
import com.oconeco.spring_search_tempo.base.service.AnalysisOverrideResult
import com.oconeco.spring_search_tempo.base.service.AnalysisOverrideService
import org.springframework.http.ResponseEntity
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
    private val analysisOverrideService: AnalysisOverrideService
) {

    /**
     * Override the analysis status of a single file.
     *
     * @param id File ID
     * @param request Override request with new status and reason
     * @param principal Current user for audit trail
     * @return Updated file info
     */
    @PostMapping("/files/{id}/override")
    fun overrideFileStatus(
        @PathVariable id: Long,
        @RequestBody request: OverrideRequest,
        principal: Principal?
    ): ResponseEntity<OverrideResponse> {
        val user = principal?.name ?: "anonymous"
        val result = analysisOverrideService.overrideFileStatus(id, request.status, request.reason, user)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result.toResponse())
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
    fun overrideFolderStatus(
        @PathVariable id: Long,
        @RequestBody request: OverrideRequest,
        principal: Principal?
    ): ResponseEntity<OverrideResponse> {
        val user = principal?.name ?: "anonymous"
        val result = analysisOverrideService.overrideFolderStatus(id, request.status, request.reason, user)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result.toResponse())
    }

    /**
     * Bulk override analysis status for multiple files.
     *
     * @param request Bulk override request with file IDs and new status
     * @param principal Current user for audit trail
     * @return List of override results
     */
    @PostMapping("/files/bulk-override")
    fun bulkOverrideFileStatus(
        @RequestBody request: BulkOverrideRequest,
        principal: Principal?
    ): ResponseEntity<BulkOverrideResponse> {
        val user = principal?.name ?: "anonymous"
        val result = analysisOverrideService.bulkOverrideFileStatus(request.ids, request.status, request.reason, user)
        return ResponseEntity.ok(result.toResponse())
    }

    /**
     * Bulk override analysis status for multiple folders.
     *
     * @param request Bulk override request with folder IDs and new status
     * @param principal Current user for audit trail
     * @return List of override results
     */
    @PostMapping("/folders/bulk-override")
    fun bulkOverrideFolderStatus(
        @RequestBody request: BulkOverrideRequest,
        principal: Principal?
    ): ResponseEntity<BulkOverrideResponse> {
        val user = principal?.name ?: "anonymous"
        val result = analysisOverrideService.bulkOverrideFolderStatus(request.ids, request.status, request.reason, user)
        return ResponseEntity.ok(result.toResponse())
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

private fun AnalysisOverrideResult.toResponse(): OverrideResponse =
    OverrideResponse(
        id = id,
        uri = uri,
        entityType = entityType,
        oldStatus = oldStatus,
        newStatus = newStatus,
        reason = reason,
        updatedBy = updatedBy,
        updatedAt = updatedAt
    )

private fun AnalysisOverrideBatchResult.toResponse(): BulkOverrideResponse =
    BulkOverrideResponse(
        updated = updated.map { it.toResponse() },
        errors = errors,
        totalRequested = totalRequested,
        totalUpdated = totalUpdated
    )
