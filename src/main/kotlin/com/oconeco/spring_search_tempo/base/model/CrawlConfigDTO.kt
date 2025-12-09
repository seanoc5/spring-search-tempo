package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull

class CrawlConfigDTO {

    var id: Long? = null

    @NotNull
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

    var jobRunId: Long? = null

    @NotNull
    var name: String? = null

    var displayLabel: String? = null

    var enabled: Boolean = true

    var startPaths: List<String>? = null

    var maxDepth: Int? = null

    var followLinks: Boolean? = null

    var parallel: Boolean? = null

    var folderPatternsSkip: String? = null

    var folderPatternsLocate: String? = null

    var folderPatternsIndex: String? = null

    var folderPatternsAnalyze: String? = null

    var filePatternsSkip: String? = null

    var filePatternsLocate: String? = null

    var filePatternsIndex: String? = null

    var filePatternsAnalyze: String? = null

}
