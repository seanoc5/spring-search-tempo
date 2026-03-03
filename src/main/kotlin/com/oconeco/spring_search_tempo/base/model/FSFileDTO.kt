package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull


class FSFileDTO {

    var id: Long? = null

    @NotNull
    @FSFileUriUnique
    var uri: String? = null

    var status: Status? = Status.NEW

    var analysisStatus: AnalysisStatus? = AnalysisStatus.LOCATE

    var label: String? = null

    var description: String? = null

    var type: String? = null

    var crawlDepth: Int? = null

    var size: Long? = null

    @NotNull
    var version: Long? = null

    var archived: Boolean? = null

    var owner: String? = null

    var group: String? = null

    var permissions: String? = null

    var fsLastModified: java.time.OffsetDateTime? = null

    var bodyText: String? = null

    var bodySize: Long? = null

    var fsFolder: Long? = null

    // Document metadata fields (extracted by Tika)
    var author: String? = null

    var title: String? = null

    var subject: String? = null

    var keywords: String? = null

    var comments: String? = null

    var creationDate: String? = null

    var modifiedDate: String? = null

    var language: String? = null

    var contentType: String? = null

    var pageCount: Int? = null

    var jobRunId: Long? = null

    var sourceHost: String? = null

    /**
     * Timestamp when this file was last chunked into ContentChunks.
     * Used to determine if re-chunking is needed.
     */
    var chunkedAt: java.time.OffsetDateTime? = null

    /**
     * Transient flag indicating text extraction failed due to permission issues.
     * Used during batch processing to track access denied files.
     * Not persisted to database.
     */
    @Transient
    var accessDenied: Boolean = false

    /**
     * Transient flag indicating text extraction failed due to other errors
     * (not permission-related, e.g., parser crash, corrupted file).
     * Not persisted to database.
     */
    @Transient
    var extractionError: Boolean = false

    /** When filesystem metadata was last synced during discovery */
    var locatedAt: java.time.OffsetDateTime? = null

    /** True if a SKIP pattern matched during discovery (inherited from parent) */
    var skipDetected: Boolean? = null

    /** Explains why the current analysisStatus was assigned */
    var analysisStatusReason: String? = null

    /** Who/what assigned the current analysisStatus (PATTERN, MANUAL, INHERITED, DEFAULT) */
    var analysisStatusSetBy: String? = null

    /** When Tika text extraction was performed */
    var indexedAt: java.time.OffsetDateTime? = null

    /** Tika error message if extraction failed */
    var indexError: String? = null

    /** JSON metadata for archive files (entry names/sizes) */
    var archiveContents: String? = null

}
