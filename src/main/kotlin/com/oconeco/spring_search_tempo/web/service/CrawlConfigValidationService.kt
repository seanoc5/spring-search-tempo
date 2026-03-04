package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.web.model.BaselineCaptureRequestDTO
import com.oconeco.spring_search_tempo.web.model.BaselineCaptureResultDTO
import com.oconeco.spring_search_tempo.web.model.FolderValidationDiffDTO
import com.oconeco.spring_search_tempo.web.model.ValidationFilterDTO
import com.oconeco.spring_search_tempo.web.model.ValidationFolderSummaryDTO

/**
 * Contract for CrawlConfig baseline capture + validation diff workflows.
 *
 * This service is intended for the CrawlConfig detail "Validate" UI and supports:
 * - Capturing capped baseline file-manifest samples per folder
 * - Comparing current DB-backed file state to baseline snapshots
 * - Recomputing pattern-assignment expectations as configs evolve
 */
interface CrawlConfigValidationService {

    /**
     * Folder-level summary for a CrawlConfig.
     */
    fun getFolderSummaries(crawlConfigId: Long): List<ValidationFolderSummaryDTO>

    /**
     * File-level diff for one folder under a CrawlConfig.
     */
    fun getFolderDiff(
        crawlConfigId: Long,
        folderId: Long,
        filter: ValidationFilterDTO = ValidationFilterDTO()
    ): FolderValidationDiffDTO

    /**
     * Capture or refresh baseline sample manifest for a folder.
     */
    fun captureFolderBaseline(
        crawlConfigId: Long,
        folderId: Long,
        request: BaselineCaptureRequestDTO = BaselineCaptureRequestDTO()
    ): BaselineCaptureResultDTO

    /**
     * Clear baseline manifest fields for one folder.
     */
    fun clearFolderBaseline(crawlConfigId: Long, folderId: Long): Boolean

    /**
     * Recompute diff using current rules and latest DB state.
     * Equivalent to getFolderDiff but explicitly expresses "refresh now" intent for UI actions.
     */
    fun recomputeFolderDiff(
        crawlConfigId: Long,
        folderId: Long,
        filter: ValidationFilterDTO = ValidationFilterDTO()
    ): FolderValidationDiffDTO
}
