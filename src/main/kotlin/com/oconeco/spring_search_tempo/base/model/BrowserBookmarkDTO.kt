package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime


class BrowserBookmarkDTO {

    var id: Long? = null

    @NotNull
    var uri: String? = null

    var status: Status? = Status.NEW

    var analysisStatus: AnalysisStatus? = AnalysisStatus.SEMANTIC

    var label: String? = null

    var description: String? = null

    var type: String? = null

    var crawlDepth: Int? = null

    var size: Long? = null

    @NotNull
    var version: Long? = null

    var archived: Boolean? = null

    var jobRunId: Long? = null

    // Browser bookmark-specific fields
    var firefoxPlaceId: Long? = null

    var firefoxBookmarkId: Long? = null

    @NotNull
    var url: String? = null

    var title: String? = null

    var domain: String? = null

    var scheme: String? = null

    var visitCount: Int? = null

    var lastVisitDate: OffsetDateTime? = null

    var frecency: Int? = null

    var dateAdded: OffsetDateTime? = null

    var folderPath: String? = null

    var bodyText: String? = null

    var fetchedAt: OffsetDateTime? = null

    var chunkedAt: OffsetDateTime? = null

    var browserProfileId: Long? = null

    /**
     * Tag names for display (not persisted directly).
     */
    var tagNames: List<String> = emptyList()

}
