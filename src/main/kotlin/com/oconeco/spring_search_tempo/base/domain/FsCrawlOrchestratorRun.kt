package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import java.time.OffsetDateTime

/**
 * One "Run All Enabled" sweep launched by `FsCrawlOrchestrator` (issue #139).
 *
 * Persists aggregate accounting for an entire sequential sweep so operators
 * can audit past sweeps from the /admin/crawl/orchestrator-runs view.
 * Per-crawl detail lives on the linked [FsCrawlOrchestratorOutcome] rows.
 */
@Entity
class FsCrawlOrchestratorRun : SaveableObject() {

    @Column(nullable = false)
    var startedAt: OffsetDateTime = OffsetDateTime.now()

    @Column
    var finishedAt: OffsetDateTime? = null

    @Column(nullable = false)
    var totalCrawls: Int = 0

    @Column(nullable = false)
    var succeeded: Int = 0

    @Column(nullable = false)
    var failed: Int = 0

    @Column(nullable = false)
    var skipped: Int = 0

    @Column(columnDefinition = "text")
    var triggeredBy: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var runStatus: FsCrawlOrchestratorRunStatus = FsCrawlOrchestratorRunStatus.RUNNING

    @OneToMany(
        mappedBy = "orchestratorRun",
        cascade = [CascadeType.ALL],
        orphanRemoval = true
    )
    @OrderBy("startedAt ASC, id ASC")
    var crawlOutcomes: MutableList<FsCrawlOrchestratorOutcome> = mutableListOf()
}

enum class FsCrawlOrchestratorRunStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
