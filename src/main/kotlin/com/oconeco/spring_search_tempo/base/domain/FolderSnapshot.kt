package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table

/**
 * One row per folder visited during a [FolderAuditRun] (issue #103).
 *
 * Disposable scratch — the table grows by one row per directory walked
 * each audit pass, so a follow-up retention policy is expected. Snapshot
 * rows are NEVER consulted by the live crawl/index path; that's the whole
 * point of keeping `fs_folder` separate.
 *
 * The two nullable pattern columns carry the audit's findings:
 * - [underSkipPattern] — non-null means this folder lives under a SKIP root.
 *   This is how the audit decides whether the folder is a "hidden gem"
 *   candidate.
 * - [matchedIndexPattern] — non-null means the folder's name matches an
 *   INDEX/ANALYZE pattern despite being under SKIP. The combination
 *   (`underSkipPattern IS NOT NULL AND matchedIndexPattern IS NOT NULL`)
 *   is the canonical "hidden gem" filter — see issue B (browse + reclassify).
 *
 * Indexed by `(audit_run_id, path)` for browse queries within a run and
 * by `(audit_run_id, under_skip_pattern)` for the hidden-gem query.
 */
@Entity
@Table(
    name = "folder_snapshot",
    indexes = [
        Index(name = "idx_folder_snapshot_run_path", columnList = "audit_run_id, path"),
        Index(name = "idx_folder_snapshot_run_skip", columnList = "audit_run_id, under_skip_pattern")
    ]
)
class FolderSnapshot {

    @Id
    @SequenceGenerator(
        name = "folder_snapshot_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "folder_snapshot_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @ManyToOne(optional = false)
    @JoinColumn(name = "audit_run_id", nullable = false)
    var auditRun: FolderAuditRun? = null

    @Column(nullable = false, columnDefinition = "text")
    var path: String? = null

    @Column(name = "parent_path", columnDefinition = "text")
    var parentPath: String? = null

    @Column(nullable = false)
    var depth: Int = 0

    /**
     * Non-null iff this folder is *under* a SKIP-pattern root. Stores the
     * matched SKIP pattern so the UI can group hidden-gem candidates by
     * which SKIP rule is hiding them.
     */
    @Column(name = "under_skip_pattern", columnDefinition = "text")
    var underSkipPattern: String? = null

    /**
     * Non-null iff this folder's path matches an INDEX/ANALYZE pattern.
     * Populated even when the folder is NOT under SKIP — the hidden-gem
     * query filters on `under_skip_pattern IS NOT NULL`, but having the
     * matched index pattern recorded universally avoids a re-walk if the
     * UI later wants "match this index pattern" as a separate facet.
     */
    @Column(name = "matched_index_pattern", columnDefinition = "text")
    var matchedIndexPattern: String? = null

    @Column(name = "file_count", nullable = false)
    var fileCount: Int = 0
}
