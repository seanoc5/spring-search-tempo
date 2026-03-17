package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.OffsetDateTime

@Entity
@Table(
    name = "host_crawl_session",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_host_crawl_session_job_run", columnNames = ["job_run_id"])
    ]
)
@EntityListeners(AuditingEntityListener::class)
class HostCrawlSession {

    @Id
    @SequenceGenerator(
        name = "host_crawl_session_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "host_crawl_session_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_host_id", nullable = false)
    var sourceHost: SourceHost? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crawl_config_id", nullable = false)
    var crawlConfig: CrawlConfig? = null

    @Column(name = "job_run_id", nullable = false)
    var jobRunId: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false, length = 32)
    var sessionType: HostCrawlSessionType = HostCrawlSessionType.FULL

    @Column(name = "selection_policy", length = 128)
    var selectionPolicy: String? = null

    @Column(name = "selection_reason_summary", columnDefinition = "text")
    var selectionReasonSummary: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: HostCrawlSessionStatus = HostCrawlSessionStatus.RUNNING

    @CreatedDate
    @Column(name = "started_at", nullable = false, updatable = false)
    var startedAt: OffsetDateTime? = null

    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    var lastUpdated: OffsetDateTime? = null

    @Column(name = "completed_at")
    var completedAt: OffsetDateTime? = null

    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null

    @OneToMany(mappedBy = "hostCrawlSession")
    var folders: MutableList<HostCrawlSessionFolder> = mutableListOf()
}

enum class HostCrawlSessionType {
    FULL,
    SMART,
    USER_REQUESTED,
    DISCOVERY_REVIEW,
    RETRY
}

enum class HostCrawlSessionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
