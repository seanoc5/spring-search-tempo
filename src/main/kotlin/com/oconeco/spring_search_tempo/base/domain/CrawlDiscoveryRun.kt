package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.OffsetDateTime

@Entity
@Table(
    name = "crawl_discovery_run",
    indexes = [
        Index(name = "idx_crawl_discovery_run_cfg_host_started", columnList = "crawl_config_id,host,started_at"),
        Index(name = "idx_crawl_discovery_run_job_run", columnList = "job_run_id")
    ]
)
@EntityListeners(AuditingEntityListener::class)
class CrawlDiscoveryRun {

    @Id
    @SequenceGenerator(
        name = "crawl_discovery_run_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "crawl_discovery_run_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crawl_config_id", nullable = false)
    var crawlConfig: CrawlConfig? = null

    @Column(name = "job_run_id")
    var jobRunId: Long? = null

    @Column(nullable = false, length = 100)
    var host: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "run_status", nullable = false, length = 20)
    var runStatus: RunStatus = RunStatus.RUNNING

    @CreatedDate
    @Column(name = "started_at", nullable = false, updatable = false)
    var startedAt: OffsetDateTime? = null

    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    var lastUpdated: OffsetDateTime? = null

    @Column(name = "completed_at")
    var completedAt: OffsetDateTime? = null

    @Column(name = "reapply_completed_at")
    var reapplyCompletedAt: OffsetDateTime? = null

    @Column(name = "reapply_changed_count", nullable = false)
    var reapplyChangedCount: Int = 0

    @Column(name = "observed_folder_count", nullable = false)
    var observedFolderCount: Int = 0
}
