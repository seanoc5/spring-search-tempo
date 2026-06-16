package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface CrawlConfigRepository : JpaRepository<CrawlConfig, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<CrawlConfig>

    fun findByNameIgnoreCaseAndSourceHostIgnoreCase(name: String, sourceHost: String): CrawlConfig?

    fun existsByNameIgnoreCaseAndSourceHostIgnoreCase(name: String, sourceHost: String): Boolean

    fun existsByNameIgnoreCaseAndSourceHostIgnoreCaseAndIdNot(name: String, sourceHost: String, id: Long): Boolean

    @Query("SELECT DISTINCT c.sourceHost FROM CrawlConfig c WHERE c.sourceHost IS NOT NULL ORDER BY c.sourceHost")
    fun findDistinctSourceHosts(): List<String>

    // Methods for ownership-based filtering
    fun findBySourceHostIn(sourceHosts: Collection<String>): List<CrawlConfig>

    fun findBySourceHostIn(sourceHosts: Collection<String>, pageable: Pageable): Page<CrawlConfig>

    fun findBySourceHostIgnoreCase(sourceHost: String): List<CrawlConfig>

    fun findBySourceHostIgnoreCase(sourceHost: String, pageable: Pageable): Page<CrawlConfig>

    fun findBySourceHostIgnoreCaseAndNameContainingIgnoreCase(
        sourceHost: String,
        name: String,
        pageable: Pageable
    ): Page<CrawlConfig>

    /**
     * All enabled crawl configs in deterministic id-ascending order. Drives
     * `FsCrawlOrchestrator.runAllEnabledCrawls` (issue #139) — sweeping by
     * id is stable across renames and matches the order rows were created.
     */
    fun findByEnabledTrueOrderByIdAsc(): List<CrawlConfig>

}
