package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime

/**
 * Idempotency + provenance record for a single message copied by an
 * `ImapMirrorService` run. Scoped by `mirrorConfigId` so the same source
 * message can be mirrored to multiple destinations without collision
 * (ADR-005).
 *
 * The dry-run feature (#25) reads this table to surface how many
 * messages a given `(mirrorConfigId, sourceFolder)` pair has already
 * mirrored — so the user sees the "skip count" before kicking off a
 * real run.
 */
@Entity
@Table(
    name = "mirrored_message",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_mirrored_message_config_messageid",
            columnNames = ["mirror_config_id", "message_id"]
        )
    ],
    indexes = [
        Index(name = "ix_mirrored_message_config_folder",
            columnList = "mirror_config_id, source_folder")
    ]
)
class MirroredMessage {

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

    @Column(name = "message_id", nullable = false, columnDefinition = "text")
    var messageId: String? = null

    @Column(name = "source_folder", nullable = false, columnDefinition = "text")
    var sourceFolder: String? = null

    @Column(name = "source_uid")
    var sourceUid: Long? = null

    @Column(name = "dest_folder", columnDefinition = "text")
    var destFolder: String? = null

    @Column(name = "dest_uid")
    var destUid: Long? = null

    @Column(name = "mirrored_at", nullable = false)
    var mirroredAt: OffsetDateTime? = null
}
