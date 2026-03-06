package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.*

/**
 * A folder discovered during remote onboarding.
 *
 * Stores folder metadata from the remote crawler's discovery phase.
 * Used in the classification UI to let users assign SKIP/LOCATE/INDEX status.
 */
@Entity
@Table(
    name = "discovered_folder",
    indexes = [
        Index(name = "idx_discovered_folder_session", columnList = "session_id"),
        Index(name = "idx_discovered_folder_path", columnList = "path"),
        Index(name = "idx_discovered_folder_depth", columnList = "depth")
    ]
)
class DiscoveredFolder {

    @Id
    @SequenceGenerator(
        name = "discovered_folder_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "discovered_folder_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    var session: DiscoverySession? = null

    /** Full path on the remote machine */
    @Column(nullable = false, columnDefinition = "text")
    var path: String? = null

    /** Folder name (last segment of path) */
    @Column(nullable = false)
    var name: String? = null

    /** Depth from discovery root */
    @Column(nullable = false)
    var depth: Int = 0

    /** Number of immediate child folders */
    @Column
    var folderCount: Int = 0

    /** Number of immediate child files */
    @Column
    var fileCount: Int = 0

    /** Total size of immediate children in bytes */
    @Column
    var totalSize: Long = 0

    /** Whether this is a hidden folder (starts with .) */
    @Column
    var isHidden: Boolean = false

    /** Status suggested by the crawler based on folder name patterns */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var suggestedStatus: SuggestedStatus? = null

    /** Status assigned by user in classification UI */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var assignedStatus: AnalysisStatus? = null

    /** Whether user has explicitly classified this folder */
    @Column
    var classified: Boolean = false

    /** Parent folder path (for tree building) */
    @Column(columnDefinition = "text")
    var parentPath: String? = null
}

/**
 * Status suggested by server-side templates based on OS + profile heuristics.
 */
enum class SuggestedStatus {
    SKIP,
    LOCATE,
    INDEX,
    ANALYZE,
    SEMANTIC,
    UNKNOWN
}
