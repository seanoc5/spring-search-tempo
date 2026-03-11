package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(
    name = "crawl_discovery_folder_obs",
    indexes = [
        Index(name = "idx_discovery_obs_cfg_host_last_seen", columnList = "crawl_config_id,host,last_seen_at"),
        Index(name = "idx_discovery_obs_cfg_host_path", columnList = "crawl_config_id,host,path")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_crawl_discovery_folder_obs_cfg_host_path",
            columnNames = ["crawl_config_id", "host", "path"]
        )
    ]
)
class CrawlDiscoveryFolderObservation {

    @Id
    @SequenceGenerator(
        name = "crawl_discovery_folder_obs_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "crawl_discovery_folder_obs_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crawl_config_id", nullable = false)
    var crawlConfig: CrawlConfig? = null

    @Column(nullable = false, length = 100)
    var host: String? = null

    @Column(nullable = false, columnDefinition = "text")
    var path: String? = null

    @Column(nullable = false)
    var depth: Int = 0

    @Column(nullable = false)
    var inSkipBranch: Boolean = true

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var manualOverride: DiscoveryManualOverride? = null

    @Column(nullable = false)
    var skipByCurrentRules: Boolean = false

    @Column(nullable = false)
    var lastSeenAt: OffsetDateTime? = null

    @Column
    var lastSeenJobRunId: Long? = null

    @OneToMany(mappedBy = "folderObservation", cascade = [CascadeType.ALL], orphanRemoval = true)
    var fileSamples: MutableList<CrawlDiscoveryFileSample> = mutableListOf()
}
