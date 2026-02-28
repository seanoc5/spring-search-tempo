package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime

class JobRunDTO {

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

    var crawlConfig: Long? = null

    /** Display label from the associated CrawlConfig */
    var crawlConfigDisplayLabel: String? = null

    /** Start paths from the associated CrawlConfig */
    var crawlConfigStartPaths: List<String>? = null

    var jobName: String? = null

    var springBatchJobInstanceId: String? = null

    var springBatchJobExecutionId: String? = null

    var startTime: OffsetDateTime? = null

    var finishTime: OffsetDateTime? = null

    /**
     * Last heartbeat timestamp. Updated periodically during job execution.
     * If null for a running job, heartbeat tracking isn't enabled yet.
     */
    var lastHeartbeatAt: OffsetDateTime? = null

    var runStatus: RunStatus = RunStatus.RUNNING

    var filesDiscovered: Long = 0

    var filesNew: Long = 0

    var filesUpdated: Long = 0

    var filesSkipped: Long = 0

    var filesError: Long = 0

    /**
     * Count of files that couldn't be read due to permission issues.
     * This is informational, not an error - expected when crawling system directories.
     */
    var filesAccessDenied: Long = 0

    var foldersDiscovered: Long = 0

    var foldersNew: Long = 0

    var foldersUpdated: Long = 0

    var foldersSkipped: Long = 0

    var totalItems: Long = 0

    var errorMessage: String? = null

    /**
     * Warning messages for non-fatal issues (e.g., missing start paths).
     * Stored as newline-separated strings.
     */
    var warningMessage: String? = null

    // Progress tracking fields

    /**
     * Total number of items expected to be processed.
     * Used for progress display: "Processed X of Y"
     */
    var expectedTotal: Long? = null

    /**
     * Number of items processed so far.
     */
    var processedCount: Long? = 0

    /**
     * Name/description of the current processing step.
     * e.g., "Fetching headers: INBOX"
     */
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
