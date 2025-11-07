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

}
