package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime


/**
 * Per-account aggregate of email contact activity (issue #146 — Phase 1).
 *
 * One row per (account, normalized_address). Counters and timestamps are
 * recomputed by [com.oconeco.spring_search_tempo.batch.contactgraph.EmailContactAggregationJobBuilder]
 * from the underlying [EmailMessage] rows. Counts are derived, not authoritative —
 * deleting an EmailMessage and re-running the aggregation produces the
 * up-to-date counter values (idempotent by design).
 *
 * `normalized_address` is lowercased and stripped of the `+suffix` plus-addressing
 * tag, so `Seanoc5+Newsletter@gmail.com` and `seanoc5@gmail.com` collapse to
 * the same contact row.
 */
@Entity
@Table(
    name = "email_contact",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_email_contact_account_address",
            columnNames = ["email_account_id", "normalized_address"]
        )
    ]
)
class EmailContact : SaveableObject() {

    @Column(nullable = false, columnDefinition = "text")
    var normalizedAddress: String? = null

    @Column(columnDefinition = "text")
    var displayNameLatest: String? = null

    @Column(nullable = false)
    var sentToCount: Long = 0

    @Column(nullable = false)
    var receivedFromCount: Long = 0

    @Column(nullable = false)
    var repliedToCount: Long = 0

    @Column(nullable = false)
    var repliedFromCount: Long = 0

    @Column
    var firstSeen: OffsetDateTime? = null

    @Column
    var lastSeen: OffsetDateTime? = null

    @Column(nullable = false)
    var threadsAppearedIn: Long = 0

    @Column
    var lastRecomputedAt: OffsetDateTime? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "email_account_id", nullable = false)
    var emailAccount: EmailAccount? = null
}
