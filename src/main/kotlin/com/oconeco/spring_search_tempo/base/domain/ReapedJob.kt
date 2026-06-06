package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
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
 * Audit row written by `OrphanedJobExecutionReaper` each time it marks a
 * STARTED `BatchJobExecution` as FAILED (issue #74).
 *
 * One row per reaping event. The point is observability: operators need
 * to see how often the reaper fires, against which accounts/jobs, and
 * whether reapings cluster (a cluster pattern would suggest the underlying
 * liveness mechanism is broken).
 */
@Entity
@Table(
    name = "reaped_job",
    indexes = [
        Index(name = "ix_reaped_job_reaped_at", columnList = "reaped_at DESC")
    ]
)
class ReapedJob {

    @Id
    @SequenceGenerator(
        name = "reaped_job_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reaped_job_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @Column(name = "reaped_at", nullable = false)
    var reapedAt: OffsetDateTime? = null

    @Column(name = "job_execution_id", nullable = false)
    var jobExecutionId: Long? = null

    @Column(name = "job_name", nullable = false, columnDefinition = "text")
    var jobName: String? = null

    @Column(name = "account_id")
    var accountId: Long? = null

    @Column(name = "original_started_at")
    var originalStartedAt: OffsetDateTime? = null

    /**
     * How long the orphaned row had been STARTED before being reaped.
     * Computed in Kotlin so the Thymeleaf view doesn't need to do
     * arithmetic on `OffsetDateTime`.
     */
    @get:Transient
    val ageAtReap: Duration?
        get() {
            val start = originalStartedAt ?: return null
            val end = reapedAt ?: return null
            return Duration.between(start, end)
        }

    /**
     * Human-friendly age string ("2h 5m 3s") for the admin view.
     */
    @get:Transient
    val ageAtReapDisplay: String?
        get() = ageAtReap?.let { d ->
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
