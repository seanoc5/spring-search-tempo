package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * One row per folder-audit invocation (issue #103).
 *
 * The audit pass walks every directory under the configured start paths
 * *including* under SKIP roots (bounded by `app.audit.hidden-gem-peek-depth`).
 * It is distinct from CrawlMode.DISCOVERY (which honors SKIP at the
 * visitor) and CrawlMode.ENFORCE (which extracts content). Its purpose is
 * (a) reconciling our folder count against `find / -type d`, and
 * (b) surfacing "hidden gem" folders living under SKIP roots that should
 * probably be reclassified.
 *
 * The companion [FolderSnapshot] rows hold the per-folder data and are
 * disposable scratch — re-runnable, not load-bearing for the live crawl.
 *
 * `sourceType` is future-proofed for `IMAP` and `ONEDRIVE` auditors;
 * only `FILESYSTEM` is implemented in this iteration.
 */
@Entity
@Table(name = "folder_audit_run")
class FolderAuditRun {

    @Id
    @SequenceGenerator(
        name = "folder_audit_run_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "folder_audit_run_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @Column(nullable = false)
    var started: OffsetDateTime? = null

    @Column
    var finished: OffsetDateTime? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    var sourceType: FolderAuditSourceType = FolderAuditSourceType.FILESYSTEM

    /**
     * For `FILESYSTEM`: the crawl-config id, or the literal path when the
     * audit was launched against a path that isn't tied to a CrawlConfig.
     * For `IMAP`/`ONEDRIVE`: account or container id (not implemented yet).
     */
    @Column(name = "source_ref", columnDefinition = "text")
    var sourceRef: String? = null

    @Column(name = "total_folders", nullable = false)
    var totalFolders: Long = 0

    @Column(name = "skip_subtree_count", nullable = false)
    var skipSubtreeCount: Long = 0

    @Column(name = "hidden_gem_count", nullable = false)
    var hiddenGemCount: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: FolderAuditRunStatus = FolderAuditRunStatus.RUNNING

    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null
}

enum class FolderAuditSourceType {
    /** Local filesystem walk — the only source implemented in #103. */
    FILESYSTEM,

    /** Future: IMAP folder hierarchy audit. */
    IMAP,

    /** Future: OneDrive folder hierarchy audit. */
    ONEDRIVE
}

enum class FolderAuditRunStatus {
    /** Walk in progress. */
    RUNNING,

    /** Walk finished cleanly; aggregates are final. */
    COMPLETED,

    /** Walk aborted; see [FolderAuditRun.errorMessage]. */
    FAILED
}
