package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

/**
 * Durable resolution for a folder-audit "hidden gem" candidate (issue #104).
 *
 * Keyed on (source_ref, path), NOT on a specific audit_run_id, so a
 * decision made today suppresses the same path on every future audit
 * run against the same source. The hidden-gem list view's NOT EXISTS
 * filter consults this table to hide already-resolved candidates.
 *
 * `source_ref` mirrors [FolderAuditRun.sourceRef] — same identifier
 * the audit run was tagged with at launch time.
 */
@Entity
@Table(
    name = "hidden_gem_resolution",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_hidden_gem_resolution_source_path",
            columnNames = ["source_ref", "path"]
        )
    ],
    indexes = [
        Index(name = "idx_hidden_gem_resolution_path", columnList = "path")
    ]
)
class HiddenGemResolution {

    @Id
    @SequenceGenerator(
        name = "hidden_gem_resolution_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hidden_gem_resolution_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @Column(name = "source_ref", nullable = false, columnDefinition = "text")
    var sourceRef: String? = null

    @Column(nullable = false, columnDefinition = "text")
    var path: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var resolution: HiddenGemResolutionKind = HiddenGemResolutionKind.DISMISSED

    @Column(name = "resolved_at", nullable = false)
    var resolvedAt: OffsetDateTime? = null

    @Column(name = "resolved_by", columnDefinition = "text")
    var resolvedBy: String? = null
}

enum class HiddenGemResolutionKind {
    /** Operator dismissed the candidate; keep it under SKIP. */
    DISMISSED,

    /** Operator reclassified the candidate to INDEX (full-text). */
    PROMOTED_TO_INDEX,

    /** Operator reclassified the candidate to ANALYZE (INDEX + NLP). */
    PROMOTED_TO_ANALYZE
}
