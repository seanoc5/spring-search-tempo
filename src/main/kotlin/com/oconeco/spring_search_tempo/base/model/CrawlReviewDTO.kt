package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Category for folder comparison results.
 */
enum class FolderMatchCategory {
    /** Folder exists in both filesystem and database */
    BOTH_EXIST,
    /** Folder exists in FS but not DB, and matches a SKIP pattern (expected) */
    FS_ONLY_EXPECTED,
    /** Folder exists in FS but not DB, should have been crawled (unexpected) */
    FS_ONLY_UNEXPECTED,
    /** Folder exists in DB but not on FS (deleted from filesystem?) */
    DB_ONLY
}

/**
 * Summary of folder comparison between filesystem and database.
 */
data class FolderComparisonSummary(
    val crawlConfigId: Long,
    val crawlConfigName: String,
    val totalFsCount: Int,
    val totalDbCount: Int,
    val bothExistCount: Int,
    val fsOnlyExpectedCount: Int,
    val fsOnlyUnexpectedCount: Int,
    val dbOnlyCount: Int
)

/**
 * Individual folder comparison result.
 */
data class FolderComparisonItem(
    val path: String,
    val category: FolderMatchCategory,
    /** Which skip pattern matched (if FS_ONLY_EXPECTED) */
    val matchedPattern: String? = null,
    /** Analysis status from DB (if in DB) */
    val dbAnalysisStatus: AnalysisStatus? = null,
    /** Last updated timestamp from DB (if in DB) */
    val dbLastUpdated: OffsetDateTime? = null
)

/**
 * Summary of file comparison by extension.
 */
data class FileExtensionSummary(
    val extension: String,
    val fsCount: Int,
    val dbCount: Int,
    val difference: Int,
    val percentageCovered: Double
)

/**
 * Overall file comparison summary for a crawl config.
 */
data class FileComparisonSummary(
    val crawlConfigId: Long,
    val crawlConfigName: String,
    val totalFsFiles: Int,
    val totalDbFiles: Int,
    val extensionBreakdown: List<FileExtensionSummary>
)

/**
 * Result of ad-hoc folder review (immediate children comparison).
 */
data class FolderReviewResult(
    val folder: FSFolderDTO,
    val filesOnFs: List<FileReviewItem>,
    val filesInDb: List<FSFileDTO>,
    val foldersOnFs: List<SubfolderReviewItem>,
    val foldersInDb: List<FSFolderDTO>,
    val summary: FolderReviewSummary
)

/**
 * Individual file in ad-hoc folder review.
 */
data class FileReviewItem(
    val filename: String,
    val path: String,
    val size: Long,
    val lastModified: Instant,
    /** What pattern matching predicts for this file */
    val expectedStatus: AnalysisStatus,
    /** Which pattern matched (if any) */
    val matchedPattern: String?,
    /** Whether this file exists in the database */
    val existsInDb: Boolean,
    /** The DB record if it exists */
    val dbRecord: FSFileDTO?
)

/**
 * Individual subfolder in ad-hoc folder review.
 */
data class SubfolderReviewItem(
    val name: String,
    val path: String,
    val lastModified: Instant,
    /** What pattern matching predicts for this folder */
    val expectedStatus: AnalysisStatus,
    /** Which pattern matched (if any) */
    val matchedPattern: String?,
    /** Whether this folder exists in the database */
    val existsInDb: Boolean,
    /** The DB record if it exists */
    val dbRecord: FSFolderDTO?
)

/**
 * Summary statistics for ad-hoc folder review.
 */
data class FolderReviewSummary(
    val fsFileCount: Int,
    val dbFileCount: Int,
    val matchingFileCount: Int,
    val fsOnlyFileCount: Int,
    val dbOnlyFileCount: Int,
    val fsFolderCount: Int,
    val dbFolderCount: Int,
    val matchingFolderCount: Int,
    val fsOnlyFolderCount: Int,
    val dbOnlyFolderCount: Int,
    val byExpectedStatus: Map<AnalysisStatus, Int>
)
