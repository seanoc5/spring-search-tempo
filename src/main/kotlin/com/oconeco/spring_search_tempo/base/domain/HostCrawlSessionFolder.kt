package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

@Entity
@Table(
    name = "host_crawl_session_folder",
    indexes = [
        Index(name = "idx_hcsf_session_status", columnList = "host_crawl_session_id,result_status"),
        Index(name = "idx_hcsf_remote_task_id", columnList = "remote_crawl_task_id")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_hcsf_session_path", columnNames = ["host_crawl_session_id", "selected_path"])
    ]
)
class HostCrawlSessionFolder {

    @Id
    @SequenceGenerator(
        name = "host_crawl_session_folder_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "host_crawl_session_folder_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_crawl_session_id", nullable = false)
    var hostCrawlSession: HostCrawlSession? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fs_folder_id")
    var fsFolder: FSFolder? = null

    @Column(name = "remote_crawl_task_id")
    var remoteCrawlTaskId: Long? = null

    @Column(name = "selected_path", nullable = false, columnDefinition = "text")
    var selectedPath: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, columnDefinition = "text")
    var analysisStatus: AnalysisStatus = AnalysisStatus.LOCATE

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_reason", nullable = false, length = 32)
    var selectionReason: HostCrawlSelectionReason = HostCrawlSelectionReason.REMOTE_QUEUE

    @Column(name = "selection_reason_detail", columnDefinition = "text")
    var selectionReasonDetail: String? = null

    @Column(name = "selected_at", nullable = false)
    var selectedAt: OffsetDateTime? = null

    @Column(name = "claimed_at")
    var claimedAt: OffsetDateTime? = null

    @Column(name = "completed_at")
    var completedAt: OffsetDateTime? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 32)
    var resultStatus: HostCrawlSessionFolderStatus = HostCrawlSessionFolderStatus.PENDING

    @Column(name = "files_seen")
    var filesSeen: Int? = null

    @Column(name = "files_changed")
    var filesChanged: Int? = null

    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null
}

enum class HostCrawlSelectionReason {
    REMOTE_QUEUE,
    STALE_FOLDER,
    USER_REQUESTED,
    RETRY,
    DISCOVERY_REVIEW
}

enum class HostCrawlSessionFolderStatus {
    PENDING,
    CLAIMED,
    COMPLETED,
    SKIPPED,
    FAILED,
    RETRY
}
