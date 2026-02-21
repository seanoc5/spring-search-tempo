package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.BrowserType
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime


class BrowserProfileDTO {

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

    // Browser profile-specific fields
    @NotNull
    var browserType: BrowserType? = null

    var profileName: String? = null

    var profilePath: String? = null

    var placesDbPath: String? = null

    var lastSyncAt: OffsetDateTime? = null

    var lastSyncBookmarkCount: Int? = null

    var enabled: Boolean = true

    var lastError: String? = null

    var lastErrorAt: OffsetDateTime? = null

}
