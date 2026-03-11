package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(
    name = "crawl_discovery_file_sample",
    indexes = [
        Index(name = "idx_discovery_sample_folder", columnList = "folder_obs_id")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_crawl_discovery_file_sample_slot",
            columnNames = ["folder_obs_id", "sample_slot"]
        )
    ]
)
class CrawlDiscoveryFileSample {

    @Id
    @SequenceGenerator(
        name = "crawl_discovery_file_sample_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "crawl_discovery_file_sample_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_obs_id", nullable = false)
    var folderObservation: CrawlDiscoveryFolderObservation? = null

    @Column(name = "sample_slot", nullable = false)
    var sampleSlot: Int = 1

    @Column(name = "file_name", nullable = false, columnDefinition = "text")
    var fileName: String? = null

    @Column(name = "file_size")
    var fileSize: Long? = null

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: OffsetDateTime? = null
}
