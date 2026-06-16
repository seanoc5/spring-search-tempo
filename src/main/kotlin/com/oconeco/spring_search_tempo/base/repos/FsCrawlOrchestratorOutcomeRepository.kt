package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorOutcome
import org.springframework.data.jpa.repository.JpaRepository

interface FsCrawlOrchestratorOutcomeRepository : JpaRepository<FsCrawlOrchestratorOutcome, Long> {

    fun findByOrchestratorRunIdOrderByStartedAtAsc(runId: Long): List<FsCrawlOrchestratorOutcome>
}
