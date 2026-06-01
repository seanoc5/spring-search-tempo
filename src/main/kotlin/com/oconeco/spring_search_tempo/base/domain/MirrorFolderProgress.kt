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
 * Per-(JobRun, sourceFolder) progress snapshot for `MirrorJob` (issue #33).
 *
 * `MirrorMessageReader` writes one row when it opens a source folder,
 * stamping the count of UIDs it intends to consider this run
 * (`totalConsidered`, after the resume-marker filter is applied). The
 * progress dashboard reads these rows to give a real denominator for
 * each folder row and to compute "folders complete / folders queued"
 * for the status line.
 *
 * `completedAt` is filled when the reader advances past the folder.
 * `null` means the folder is either in flight or hasn't been opened yet.
 */
@Entity
@Table(
    name = "mirror_folder_progress",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_mirror_folder_progress_run_folder",
            columnNames = ["job_run_id", "source_folder"]
        )
    ],
    indexes = [
        Index(
            name = "ix_mirror_folder_progress_run",
            columnList = "job_run_id"
        )
    ]
)
@EntityListeners(AuditingEntityListener::class)
class MirrorFolderProgress {

    @Id
    @SequenceGenerator(
        name = "mirror_folder_progress_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(
        strategy = GenerationType.SEQUENCE,
        generator = "mirror_folder_progress_sequence"
    )
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @Column(name = "mirror_config_id", nullable = false)
    var mirrorConfigId: Long? = null

    @Column(name = "job_run_id", nullable = false)
    var jobRunId: Long? = null

    @Column(name = "source_folder", nullable = false, columnDefinition = "text")
    var sourceFolder: String? = null

    @Column(name = "dest_folder", columnDefinition = "text")
    var destFolder: String? = null

    /**
     * Count of source UIDs the reader intends to walk for this folder in
     * this run, captured at folder-open time after the resume-marker
     * filter is applied. The `MirroredMessage` pre-filter pass happens
     * after this number is fixed, so this is the *denominator the user
     * sees* — i.e. the total queue size, not the post-prefilter pending
     * size.
     */
    @Column(name = "total_considered", nullable = false)
    var totalConsidered: Long = 0L

    @Column(name = "opened_at")
    var openedAt: OffsetDateTime? = null

    @Column(name = "completed_at")
    var completedAt: OffsetDateTime? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime? = null
}
