package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import java.time.OffsetDateTime

/**
 * Tracks individual job executions with statistics.
 * Each run of a crawl job creates a new JobRun record.
 */
@Entity
class JobRun : SaveableObject() {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crawl_config_id")
    var crawlConfig: CrawlConfig? = null

    @Column(columnDefinition = "text")
    var jobName: String? = null

    @Column(columnDefinition = "text")
    var springBatchJobInstanceId: String? = null

    @Column(columnDefinition = "text")
    var springBatchJobExecutionId: String? = null

    @Column
    var startTime: OffsetDateTime? = null

    @Column
    var finishTime: OffsetDateTime? = null

    /**
     * Last heartbeat timestamp. Updated periodically during job execution
     * to indicate the job is still actively processing.
     * Used for detecting stale/orphaned jobs (heartbeat > 2 minutes old = likely dead).
     */
    @Column
    var lastHeartbeatAt: OffsetDateTime? = null

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    var runStatus: RunStatus = RunStatus.RUNNING

    // Crawl statistics
    @Column
    var filesDiscovered: Long = 0

    @Column
    var filesNew: Long = 0

    @Column
    var filesUpdated: Long = 0

    @Column
    var filesSkipped: Long = 0

    @Column
    var filesError: Long = 0

    /**
     * Count of files that couldn't be read due to permission issues.
     * This is informational, not an error - expected when crawling system directories.
     */
    @Column
    var filesAccessDenied: Long = 0

    @Column
    var foldersDiscovered: Long = 0

    @Column
    var foldersNew: Long = 0

    @Column
    var foldersUpdated: Long = 0

    @Column
    var foldersSkipped: Long = 0

    @Column
    var totalItems: Long = 0

    @Column(columnDefinition = "text")
    var errorMessage: String? = null

    /**
     * Warning messages for non-fatal issues (e.g., missing start paths).
     * Stored as newline-separated strings.
     */
    @Column(columnDefinition = "text")
    var warningMessage: String? = null

    // Progress tracking fields
    /**
     * Total number of items expected to be processed.
     * Set at job start (e.g., from IMAP folder message counts).
     */
    @Column
    var expectedTotal: Long? = null

    /**
     * Number of items processed so far.
     * Updated during job execution for progress display.
     */
    @Column
    var processedCount: Long? = 0

    /**
     * Name/description of the current processing step.
     * e.g., "Fetching headers: INBOX" or "Enriching bodies"
     */
    @Column(columnDefinition = "text")
    var currentStepName: String? = null

}

enum class RunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
