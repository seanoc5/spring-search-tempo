package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.OffsetDateTime

/**
 * Tracks a discovery session from a remote crawler.
 *
 * Discovery is the first phase of onboarding a new machine:
 * 1. Remote crawler runs `onboard` command
 * 2. Crawler does fast folder-only enumeration
 * 3. Uploads folder tree to server (creates DiscoverySession + DiscoveredFolders)
 * 4. User classifies folders in UI (SKIP/LOCATE/INDEX/ANALYZE)
 * 5. Classifications are applied to create CrawlConfig or update patterns
 */
@Entity
@Table(name = "discovery_session")
@EntityListeners(AuditingEntityListener::class)
class DiscoverySession {

    @Id
    @SequenceGenerator(
        name = "discovery_session_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "discovery_session_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    /** Host that ran the discovery */
    @Column(nullable = false, length = 100)
    var host: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_host_id")
    var sourceHostRef: SourceHost? = null

    /** Operating system type: WINDOWS, LINUX, MACOS */
    @Column(nullable = false, length = 20)
    var osType: String? = null

    /** Root paths that were discovered (comma-separated) */
    @Column(columnDefinition = "text")
    var rootPaths: String? = null

    /** Current status of the discovery session */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: DiscoveryStatus = DiscoveryStatus.PENDING

    /** Total folders discovered */
    @Column
    var totalFolders: Int = 0

    /** Folders classified so far */
    @Column
    var classifiedFolders: Int = 0

    /** Count of folders marked SKIP */
    @Column
    var skipCount: Int = 0

    /** Count of folders marked LOCATE */
    @Column
    var locateCount: Int = 0

    /** Count of folders marked INDEX */
    @Column
    var indexCount: Int = 0

    /** Count of folders marked ANALYZE */
    @Column
    var analyzeCount: Int = 0

    /** How long the discovery took on the remote machine (ms) */
    @Column
    var discoveryDurationMs: Long? = null

    /** CrawlConfig created from this discovery (if any) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crawl_config_id")
    var crawlConfig: CrawlConfig? = null

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var dateCreated: OffsetDateTime? = null

    @LastModifiedDate
    @Column(nullable = false)
    var lastUpdated: OffsetDateTime? = null

    /** When classifications were applied */
    @Column
    var appliedAt: OffsetDateTime? = null

    @OneToMany(mappedBy = "session", cascade = [CascadeType.ALL], orphanRemoval = true)
    var folders: MutableList<DiscoveredFolder> = mutableListOf()
}

enum class DiscoveryStatus {
    /** Folders uploaded, awaiting classification */
    PENDING,
    /** User is classifying folders */
    CLASSIFYING,
    /** Classifications have been applied */
    APPLIED,
    /** Older session retained for history but no longer active */
    ARCHIVED,
    /** Session was cancelled/abandoned */
    CANCELLED
}
