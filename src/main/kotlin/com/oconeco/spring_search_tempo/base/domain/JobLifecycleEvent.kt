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
import jakarta.persistence.Transient
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Unified audit row for the batch-job lifecycle: rows are written when a
 * `BatchJobExecution` is forced out of `STARTED` by either the orphan
 * reaper (issue #74) or the graceful-shutdown hook (issue #75).
 *
 * Why one table for both: operators looking at "what happened to this
 * running job" don't care which sub-system intervened — they want the
 * chronological story. A single table with [eventType] + [actionTaken]
 * keeps the admin view a single sortable list with a badge column,
 * instead of two parallel logs the operator has to mentally interleave.
 *
 * Supersedes the earlier `reaped_job` table — see issue #75 for the
 * rationale.
 */
@Entity
@Table(
    name = "job_lifecycle_event",
    indexes = [
        Index(name = "ix_job_lifecycle_event_time", columnList = "event_time DESC"),
        Index(name = "ix_job_lifecycle_event_type", columnList = "event_type")
    ]
)
class JobLifecycleEvent {

    @Id
    @SequenceGenerator(
        name = "job_lifecycle_event_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "job_lifecycle_event_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @Column(name = "event_time", nullable = false)
    var eventTime: OffsetDateTime? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    var eventType: JobLifecycleEventType? = null

    /**
     * Specific action recorded for this event:
     *
     *   - `reaped` — orphan reaper marked the row FAILED at boot.
     *   - `stopped` — shutdown hook marked the in-flight row FAILED before exit.
     *   - `abandoned` — shutdown hook saw the row but couldn't update it
     *     (DB write failed during shutdown).
     *   - `shutdown_too_fast` — shutdown hook timed out before it could
     *     act on this row; the next-boot reaper will clean it up.
     */
    @Column(name = "action_taken", nullable = false, columnDefinition = "text")
    var actionTaken: String? = null

    @Column(name = "job_execution_id", nullable = false)
    var jobExecutionId: Long? = null

    @Column(name = "job_name", nullable = false, columnDefinition = "text")
    var jobName: String? = null

    @Column(name = "account_id")
    var accountId: Long? = null

    @Column(name = "original_started_at")
    var originalStartedAt: OffsetDateTime? = null

    /**
     * Free-form context (the reason string from the reaper, or the
     * exception message from the shutdown hook). Surfaced in the admin
     * view as a tooltip / expandable cell.
     */
    @Column(name = "details", columnDefinition = "text")
    var details: String? = null

    /**
     * How long the row had been STARTED before this event fired. Kept
     * in Kotlin so the Thymeleaf view doesn't have to do duration
     * arithmetic on `OffsetDateTime`.
     */
    @get:Transient
    val ageAtEvent: Duration?
        get() {
            val start = originalStartedAt ?: return null
            val end = eventTime ?: return null
            return Duration.between(start, end)
        }

    @get:Transient
    val ageAtEventDisplay: String?
        get() = ageAtEvent?.let { d ->
            val h = d.toHours()
            val m = d.toMinutesPart()
            val s = d.toSecondsPart()
            when {
                h > 0 -> "${h}h ${m}m ${s}s"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s"
            }
        }
}

enum class JobLifecycleEventType {
    /** Boot-time reaper found an orphan and marked it FAILED (#74). */
    REAPED,

    /** Graceful-shutdown hook acted on an in-flight execution (#75). */
    SHUTDOWN
}
