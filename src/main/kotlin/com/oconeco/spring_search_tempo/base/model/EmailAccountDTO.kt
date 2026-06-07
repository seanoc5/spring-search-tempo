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

    // Issue #84: see EmailAccount.lastFolderEnumeratedAt.
    var lastFolderEnumeratedAt: OffsetDateTime? = null

    // Credential configuration
    // DEPRECATED (issue #55): env-var credential fallback removed. Kept on the DTO
    // for one release so existing serialized state survives a deploy. Sole credential
    // path is now the DB-encrypted password (set via /emailAccounts/{id}/password).
    var credentialEnvVar: String? = null

    // Account status
    var enabled: Boolean = true

    // Per-account cron schedule (Spring 6-field: sec min hr dom mon dow).
    // See ADR-004.
    var cronSchedule: String = "0 0 0 * * *"

    // Last dispatch time, set by MultiAccountEmailScheduler.
    var lastDispatchedAt: OffsetDateTime? = null

    var lastError: String? = null

    var lastErrorAt: OffsetDateTime? = null

    // Issue #59: derived display state for the "Last Error" card.
    // Populated by EmailErrorDisplayHelper in the service layer; not persisted.
    var errorDisplayVisible: Boolean = false

    var errorAgeDescription: String? = null

}
