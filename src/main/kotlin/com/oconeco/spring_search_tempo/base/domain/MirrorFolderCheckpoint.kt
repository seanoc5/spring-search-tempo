package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.OffsetDateTime

/**
 * Per-folder resume marker for `MirrorJob` (issue #39).
 *
 * Sibling to [MirrorCheckpoint]: where [MirrorCheckpoint] tracks "the run is
 * in flight" with a single resume cursor, this table holds one row per
 * `(mirrorConfigId, sourceFolder)` so each folder can be resumed
 * independently. A failure in folder B won't lose folder A's progress;
 * on retry, the reader resumes each folder from its own `lastSourceUid`.
 *
 * The legacy `MirrorCheckpoint` row is still written (and cleared on
 * success) for back-compat with the existing dashboard's "current UID"
 * column.
 */
@Entity
@Table(
    name = "mirror_folder_checkpoint",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_mirror_folder_checkpoint_config_folder",
            columnNames = ["mirror_config_id", "source_folder"]
        )
    ],
    indexes = [
        Index(
            name = "ix_mirror_folder_checkpoint_config",
            columnList = "mirror_config_id"
        )
    ]
)
@EntityListeners(AuditingEntityListener::class)
class MirrorFolderCheckpoint {

    @Id
    @SequenceGenerator(
        name = "mirror_folder_checkpoint_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(
        strategy = GenerationType.SEQUENCE,
        generator = "mirror_folder_checkpoint_sequence"
    )
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @Column(name = "mirror_config_id", nullable = false)
    var mirrorConfigId: Long? = null

    @Column(name = "source_folder", nullable = false, columnDefinition = "text")
    var sourceFolder: String? = null

    @Column(name = "last_source_uid", nullable = false)
    var lastSourceUid: Long = 0L

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime? = null
}
