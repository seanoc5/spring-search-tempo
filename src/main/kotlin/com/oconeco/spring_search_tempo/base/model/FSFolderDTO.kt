package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime


class FSFolderDTO {

    var id: Long? = null

    @NotNull
    @FSFolderUriUnique
    var uri: String? = null

    var status: Status? = null

    var analysisStatus: AnalysisStatus? = null

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

    var fsLastModified: OffsetDateTime? = null

    var crawlConfigId: Long? = null

    var jobRunId: Long? = null

    var sourceHost: String? = null

    var jobRunLabel: String? = null

    /** When filesystem metadata was last synced during discovery */
    var locatedAt: java.time.OffsetDateTime? = null

    /** True if a SKIP pattern matched during discovery */
    var skipDetected: Boolean? = null

    /** Explains why the current analysisStatus was assigned */
    var analysisStatusReason: String? = null

    /** Who/what assigned the current analysisStatus (PATTERN, MANUAL, INHERITED, DEFAULT) */
    var analysisStatusSetBy: String? = null

    /** Baseline manifest JSON used for CrawlConfig validation and diff testing */
    var baselineManifest: String? = null

    /** Timestamp when baselineManifest was captured */
    var baselineCapturedAt: OffsetDateTime? = null

    /** Job run that produced the current baseline snapshot (if known) */
    var baselineSourceJobRunId: Long? = null

    /** Total files seen when baseline was captured (before capping/sampling) */
    var baselineTotalFiles: Int? = null

    /** Number of files included in baselineManifest sample */
    var baselineSampleFiles: Int? = null

    /** Sampling policy used to create baselineManifest */
    var baselineSamplingPolicy: String? = null

    /** Deterministic seed used for hash/random style sampling */
    var baselineSeed: String? = null

    /** Schema/contract version for baselineManifest payload */
    var baselineVersion: Int? = null

}
