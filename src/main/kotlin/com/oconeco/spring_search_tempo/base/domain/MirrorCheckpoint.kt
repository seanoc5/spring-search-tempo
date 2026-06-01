package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.OffsetDateTime

/**
 * Persistent resume marker for `MirrorJob` (issue #24). One row per
 * `MirrorConfig`; updated at the end of every chunk while the job is
 * running and deleted on successful completion.
 *
 * Modeled on [CrawlCheckpoint] (PR #19): the resumability shape is the
 * same, but the per-folder dimension matters here. We track
 * `currentFolder` and `lastSourceUidProcessed` so a mid-folder restart
 * resumes from `lastSourceUidProcessed + 1` inside `currentFolder`
 * rather than rewalking everything.
 */
@Entity
@Table(
    name = "mirror_checkpoint",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_mirror_checkpoint_config", columnNames = ["mirror_config_id"])
    ]
)
@EntityListeners(AuditingEntityListener::class)
class MirrorCheckpoint {

    @Id
    @SequenceGenerator(
        name = "mirror_checkpoint_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "mirror_checkpoint_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @Column(name = "mirror_config_id", nullable = false)
    var mirrorConfigId: Long? = null

    @Column(name = "current_folder", nullable = false, columnDefinition = "text")
    var currentFolder: String? = null

    @Column(name = "last_source_uid_processed", nullable = false)
    var lastSourceUidProcessed: Long? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime? = null
}
