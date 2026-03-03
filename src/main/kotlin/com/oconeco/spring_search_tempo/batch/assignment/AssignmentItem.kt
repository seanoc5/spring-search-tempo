package com.oconeco.spring_search_tempo.batch.assignment

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus

/**
 * Item for folder analysis status assignment.
 */
data class FolderAssignmentItem(
    val id: Long,
    val uri: String,
    val parentUri: String?,
    val currentStatus: AnalysisStatus?,
    val currentSetBy: String?
)

/**
 * Item for file analysis status assignment.
 */
data class FileAssignmentItem(
    val id: Long,
    val uri: String,
    val parentFolderId: Long?,
    val parentFolderUri: String?,
    val currentStatus: AnalysisStatus?,
    val currentSetBy: String?
)

/**
 * Result of analysis status assignment.
 */
data class AssignmentResult(
    val id: Long,
    val entityType: String, // "FOLDER" or "FILE"
    val newStatus: AnalysisStatus,
    val reason: String,
    val setBy: String // "PATTERN", "INHERITED", "DEFAULT"
)
