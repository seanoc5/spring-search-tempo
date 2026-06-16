package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorRun
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorRunStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface FsCrawlOrchestratorRunRepository : JpaRepository<FsCrawlOrchestratorRun, Long> {

    fun findByRunStatusOrderByStartedAtDesc(status: FsCrawlOrchestratorRunStatus): List<FsCrawlOrchestratorRun>

    fun countByRunStatus(status: FsCrawlOrchestratorRunStatus): Long

    @Query(
        "SELECT DISTINCT r FROM FsCrawlOrchestratorRun r " +
            "LEFT JOIN FETCH r.crawlOutcomes " +
            "WHERE r.id = :id"
    )
    fun findByIdWithOutcomes(id: Long): FsCrawlOrchestratorRun?

    @Query(
        value = "SELECT r FROM FsCrawlOrchestratorRun r ORDER BY r.startedAt DESC",
        countQuery = "SELECT COUNT(r) FROM FsCrawlOrchestratorRun r"
    )
    fun findAllByStartedAtDesc(pageable: Pageable): Page<FsCrawlOrchestratorRun>
}
