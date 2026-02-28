package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime


class OneDriveAccountDTO {

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

    // Microsoft identity
    var microsoftAccountId: String? = null

    var displayName: String? = null

    var email: String? = null

    // OAuth2 / PKCE
    @NotNull
    var clientId: String? = null

    var encryptedRefreshToken: String? = null

    var tokenObtainedAt: OffsetDateTime? = null

    // Drive info
    var driveId: String? = null

    var driveType: String? = null

    var driveQuotaTotal: Long? = null

    var driveQuotaUsed: Long? = null

    // Delta sync state
    var deltaToken: String? = null

    var lastDeltaSyncAt: OffsetDateTime? = null

    var lastFullSyncAt: OffsetDateTime? = null

    // Account status
    var enabled: Boolean = true

    var lastError: String? = null

    var lastErrorAt: OffsetDateTime? = null

    // Aggregated stats
    var totalItems: Long = 0

    var totalSize: Long = 0

}
