package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime


class EmailAccountDTO {

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

    // Email-specific fields
    @NotNull
    var provider: EmailProvider? = null

    @NotNull
    @Email
    var email: String? = null

    var displayName: String? = null

    // IMAP settings
    var imapHost: String? = null

    var imapPort: Int? = null

    var useSsl: Boolean = true

    // Sync state
    var inboxLastSyncUid: Long? = null

    var sentLastSyncUid: Long? = null

    var lastQuickSyncAt: OffsetDateTime? = null

    var lastFullSyncAt: OffsetDateTime? = null

    var lastFullSyncFolderCount: Int? = null

    // Account status
    var enabled: Boolean = true

    var lastError: String? = null

    var lastErrorAt: OffsetDateTime? = null

}
