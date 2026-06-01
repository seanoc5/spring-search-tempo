package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * Per-message failure record from a `MirrorJob` run (issue #26).
 *
 * One row per source UID that `ImapMirrorService` couldn't copy. The
 * `retryable` flag carries the classification produced by
 * `MirrorResult.Failed.retryable` so the retry endpoint can re-queue
 * just the transient failures without rescanning the source folder.
 *
 * Rows are scoped by `mirrorConfigId` (the row that survives the
 * config across runs) and `jobRunId` (the specific run during which
 * the failure occurred), so the dashboard can show errors from the
 * active run while the history table still reaches back across earlier
 * runs of the same config.
 */
@Entity
@Table(
    name = "mirror_error",
    indexes = [
        Index(name = "ix_mirror_error_config_occurred",
            columnList = "mirror_config_id, occurred_at"),
        Index(name = "ix_mirror_error_jobrun",
            columnList = "job_run_id"),
        Index(name = "ix_mirror_error_config_retryable",
            columnList = "mirror_config_id, retryable")
    ]
)
class MirrorError {

    @Id
    @SequenceGenerator(
        name = "primary_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(
        strategy = GenerationType.SEQUENCE,
        generator = "primary_sequence"
    )
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @Column(name = "mirror_config_id", nullable = false)
    var mirrorConfigId: Long? = null

    @Column(name = "job_run_id")
    var jobRunId: Long? = null

    @Column(name = "source_folder", nullable = false, columnDefinition = "text")
    var sourceFolder: String? = null

    @Column(name = "source_uid", nullable = false)
    var sourceUid: Long? = null

    @Column(name = "message_id", columnDefinition = "text")
    var messageId: String? = null

    @Column(name = "dest_folder", columnDefinition = "text")
    var destFolder: String? = null

    @Column(nullable = false, columnDefinition = "text")
    var reason: String? = null

    @Column(nullable = false)
    var retryable: Boolean = false

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: OffsetDateTime? = null
}
