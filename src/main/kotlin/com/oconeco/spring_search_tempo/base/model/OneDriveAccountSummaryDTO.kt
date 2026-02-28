package com.oconeco.spring_search_tempo.base.model

import java.time.OffsetDateTime


/**
 * Summary DTO for OneDrive account list display.
 * Includes drive info, sync state, and active job progress.
 */
class OneDriveAccountSummaryDTO {

    var id: Long? = null

    var email: String? = null

    var displayName: String? = null

    var enabled: Boolean = true

    // Drive info
    var driveId: String? = null

    var driveType: String? = null

    var driveQuotaTotal: Long? = null

    var driveQuotaUsed: Long? = null

    // Sync state
    var lastDeltaSyncAt: OffsetDateTime? = null

    var lastError: String? = null

    var lastErrorAt: OffsetDateTime? = null

    // Aggregated counts
    var totalItems: Long = 0

    var totalSize: Long = 0

    /**
     * Whether the account has a valid refresh token stored.
     */
    var isConnected: Boolean = false

    // Active job progress (null if no job running)
    var activeJobRun: JobRunProgressDTO? = null

}
