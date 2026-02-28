package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.EmailProvider
import java.time.OffsetDateTime


/**
 * Summary DTO for email account list display.
 * Includes aggregated counts and active job progress.
 */
class EmailAccountSummaryDTO {

    var id: Long? = null

    var email: String? = null

    var label: String? = null

    var provider: EmailProvider? = null

    var enabled: Boolean = true

    // Sync state
    var lastQuickSyncAt: OffsetDateTime? = null

    var lastError: String? = null

    var lastErrorAt: OffsetDateTime? = null

    // Aggregated counts
    var folderCount: Long = 0

    var messageCount: Long = 0

    var unreadCount: Long = 0

    // Active job progress (null if no job running)
    var activeJobRun: JobRunProgressDTO? = null

}


/**
 * Lightweight progress info for display in account list.
 */
class JobRunProgressDTO {

    var id: Long? = null

    var expectedTotal: Long? = null

    var processedCount: Long? = 0

    var currentStepName: String? = null

    /**
     * Computed progress percentage (0-100).
     * Returns null if expectedTotal is not set.
     */
    val progressPercent: Int?
        get() = expectedTotal?.let { total ->
            val processed = processedCount ?: 0
            if (total > 0) ((processed * 100) / total).toInt().coerceIn(0, 100) else 0
        }

}
