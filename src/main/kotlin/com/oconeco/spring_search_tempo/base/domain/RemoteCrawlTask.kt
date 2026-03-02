package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(
    name = "remote_crawl_task",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_remote_crawl_task_session_uri", columnNames = ["session_id", "remote_uri"])
    ]
)
class RemoteCrawlTask {

    @Id
    @SequenceGenerator(
        name = "primary_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 200,
        initialValue = 10000
    )
    @GeneratedValue(
        strategy = GenerationType.SEQUENCE,
        generator = "primary_sequence"
    )
    var id: Long? = null

    @Column(name = "session_id", nullable = false)
    var sessionId: Long? = null

    @Column(name = "crawl_config_id", nullable = false)
    var crawlConfigId: Long? = null

    @Column(nullable = false, length = 50)
    var host: String? = null

    @Column(name = "folder_path", nullable = false, columnDefinition = "text")
    var folderPath: String? = null

    @Column(name = "remote_uri", nullable = false, columnDefinition = "text")
    var remoteUri: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, columnDefinition = "text")
    var analysisStatus: AnalysisStatus = AnalysisStatus.LOCATE

    @Enumerated(EnumType.STRING)
    @Column(name = "task_status", nullable = false, columnDefinition = "text")
    var taskStatus: RemoteTaskStatus = RemoteTaskStatus.PENDING

    @Column(name = "claim_token", columnDefinition = "text")
    var claimToken: String? = null

    @Column(name = "claimed_at")
    var claimedAt: OffsetDateTime? = null

    @Column(name = "completed_at")
    var completedAt: OffsetDateTime? = null

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @Column(name = "priority", nullable = false)
    var priority: Int = 0

    @Column(name = "depth")
    var depth: Int? = null

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null

    @CreatedDate
    @Column(name = "date_created", nullable = false, updatable = false)
    var dateCreated: OffsetDateTime? = null

    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    var lastUpdated: OffsetDateTime? = null
}

enum class RemoteTaskStatus {
    PENDING,
    CLAIMED,
    COMPLETED,
    FAILED,
    SKIPPED
}
