package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import java.time.OffsetDateTime

/**
 * Per-crawl outcome inside an [FsCrawlOrchestratorRun] sweep (issue #139).
 *
 * One row per `CrawlConfig` the orchestrator iterated over — failures sit
 * here alongside successes so the run-summary drilldown can render them
 * uniformly.
 */
@Entity
class FsCrawlOrchestratorOutcome : SaveableObject() {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "orchestrator_run_id", nullable = false)
    var orchestratorRun: FsCrawlOrchestratorRun? = null

    @Column
    var crawlConfigId: Long? = null

    @Column(columnDefinition = "text")
    var crawlConfigName: String? = null

    @Column(columnDefinition = "text")
    var crawlConfigLabel: String? = null

    @Column(columnDefinition = "text")
    var jobExecutionId: String? = null

    @Column(columnDefinition = "text")
    var batchStatus: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var outcome: FsCrawlOutcomeStatus = FsCrawlOutcomeStatus.PENDING

    @Column
    var startedAt: OffsetDateTime? = null

    @Column
    var finishedAt: OffsetDateTime? = null

    @Column
    var elapsedMs: Long? = null

    @Column(columnDefinition = "text")
    var errorMessage: String? = null
}

enum class FsCrawlOutcomeStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
