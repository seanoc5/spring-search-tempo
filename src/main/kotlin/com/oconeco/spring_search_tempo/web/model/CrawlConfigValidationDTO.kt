package com.oconeco.spring_search_tempo.web.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import java.time.OffsetDateTime

/**
 * Sampling policies for baseline manifest capture.
 */
enum class BaselineSamplingPolicy {
    /** Representative capped sample (recommended default for UI test data). */
    REPRESENTATIVE_50,
    /** First N files by stable sort order. */
    FIRST_N,
    /** Stable hash-based sample using a deterministic seed. */
    HASH_STABLE
}

/**
 * High-level source of pattern used to determine effective analysis status.
 */
enum class ValidationPatternSource {
    NONE,
    DEFAULT_CONFIG,
    CRAWL_CONFIG,
    FOLDER_OVERRIDE,
    INHERITED
}

/**
 * File-level diff classification for validator output.
 */
enum class ValidationDiffType {
    OK,
    STATUS_DRIFT,
    ASSIGNMENT_MISMATCH,
    METADATA_DRIFT,
    MISSING_IN_CURRENT,
    NEW_IN_CURRENT
}

/**
 * Request contract for capturing/updating folder baseline test data.
 */
data class BaselineCaptureRequestDTO(
    val maxSamples: Int = 50,
    val samplingPolicy: BaselineSamplingPolicy = BaselineSamplingPolicy.REPRESENTATIVE_50,
    val seed: String? = null
)

/**
 * Result for baseline capture/update.
 */
data class BaselineCaptureResultDTO(
    val crawlConfigId: Long,
    val folderId: Long,
    val folderPath: String,
    val capturedAt: OffsetDateTime,
    val totalFileCount: Int,
    val sampleFileCount: Int,
    val samplingPolicy: BaselineSamplingPolicy,
    val seed: String?,
    val truncated: Boolean
)

/**
 * File metadata shape persisted in the baseline manifest snapshot.
 */
data class BaselineManifestFileDTO(
    val relPath: String,
    val name: String,
    val ext: String?,
    val size: Long?,
    val mtime: OffsetDateTime?,
    val hidden: Boolean
)

/**
 * Filter contract for folder diff view.
 */
data class ValidationFilterDTO(
    val onlyMismatches: Boolean = false,
    val onlyStatusDrift: Boolean = false,
    val onlyMissingOrNew: Boolean = false,
    val statusFilter: AnalysisStatus? = null,
    val patternSourceFilter: ValidationPatternSource? = null
)

/**
 * Pattern matching trace for an evaluated file.
 */
data class PatternMatchTraceDTO(
    val effectiveStatus: AnalysisStatus?,
    val matchedPattern: String?,
    val patternSource: ValidationPatternSource = ValidationPatternSource.NONE,
    val reason: String? = null
)

/**
 * One row in folder-level validation diff.
 */
data class ValidationFileDiffRowDTO(
    val path: String,
    val name: String,
    val inBaseline: Boolean,
    val inCurrent: Boolean,
    val baselineSize: Long?,
    val currentSize: Long?,
    val baselineMtime: OffsetDateTime?,
    val currentMtime: OffsetDateTime?,
    val dbAssignedStatus: AnalysisStatus?,
    val baselinePredictedStatus: AnalysisStatus?,
    val currentPredictedStatus: AnalysisStatus?,
    val baselineTrace: PatternMatchTraceDTO?,
    val currentTrace: PatternMatchTraceDTO?,
    val diffType: ValidationDiffType
)

/**
 * Aggregated counters for folder validation diff.
 */
data class ValidationDiffTotalsDTO(
    val totalRows: Int,
    val ok: Int,
    val statusDrift: Int,
    val assignmentMismatch: Int,
    val metadataDrift: Int,
    val missingInCurrent: Int,
    val newInCurrent: Int
)

/**
 * Folder-level diff payload for validator UI.
 */
data class FolderValidationDiffDTO(
    val crawlConfigId: Long,
    val folderId: Long,
    val folderPath: String,
    val baselineCapturedAt: OffsetDateTime?,
    val baselineTotalFiles: Int?,
    val baselineSampleFiles: Int?,
    val currentFileCount: Int,
    val filter: ValidationFilterDTO,
    val totals: ValidationDiffTotalsDTO,
    val rows: List<ValidationFileDiffRowDTO>
)

/**
 * One row for folder-level summary panel.
 */
data class ValidationFolderSummaryDTO(
    val crawlConfigId: Long,
    val folderId: Long,
    val folderPath: String,
    val baselineCapturedAt: OffsetDateTime?,
    val baselineSampleFiles: Int?,
    val baselineTotalFiles: Int?,
    val currentFileCount: Int,
    val mismatchCount: Int,
    val statusDriftCount: Int,
    val missingInCurrentCount: Int,
    val newInCurrentCount: Int
)
