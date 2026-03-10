package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull

class CrawlConfigDTO {

    var id: Long? = null

    var uri: String? = null

    var status: Status? = Status.NEW

    var analysisStatus: AnalysisStatus? = AnalysisStatus.LOCATE

    var label: String? = null

    var description: String? = null

    var type: String? = null

    var crawlDepth: Int? = null

    var size: Long? = null

    var version: Long? = null

    var archived: Boolean? = null

    var jobRunId: Long? = null

    var sourceHost: String? = null

    @NotNull
    var name: String? = null

    var startPaths: List<String>? = null

    var maxDepth: Int? = null

    var followLinks: Boolean? = null

    var parallel: Boolean? = null

    var folderPatternsSkip: String? = null

    var folderPatternsLocate: String? = null

    var folderPatternsIndex: String? = null

    var folderPatternsAnalyze: String? = null

    var folderPatternsSemantic: String? = null

    var filePatternsSkip: String? = null

    var filePatternsLocate: String? = null

    var filePatternsIndex: String? = null

    var filePatternsAnalyze: String? = null

    var filePatternsSemantic: String? = null

    var folderPrioritySkip: Int = 500

    var folderPrioritySemantic: Int = 400

    var folderPriorityAnalyze: Int = 300

    var folderPriorityIndex: Int = 200

    var folderPriorityLocate: Int = 100

    var filePrioritySkip: Int = 500

    var filePrioritySemantic: Int = 400

    var filePriorityAnalyze: Int = 300

    var filePriorityIndex: Int = 200

    var filePriorityLocate: Int = 100

    /**
     * Hours threshold for considering folders "recently crawled" by this config.
     * Null means use system default.
     */
    var freshnessHours: Int? = null

    // ============ Smart Crawl Scheduling ============

    /**
     * Enable temperature-based crawl scheduling for this config.
     */
    var smartCrawlEnabled: Boolean = false

    /**
     * Days threshold for HOT temperature (default 7).
     */
    var hotThresholdDays: Int? = null

    /**
     * Days threshold for WARM temperature (default 30).
     */
    var warmThresholdDays: Int? = null

}

