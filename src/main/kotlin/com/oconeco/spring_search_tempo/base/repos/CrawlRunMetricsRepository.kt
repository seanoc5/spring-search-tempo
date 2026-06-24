package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.CrawlRunMetrics
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CrawlRunMetricsRepository : JpaRepository<CrawlRunMetrics, Long> {

    /**
     * Pageable-respecting filter — callers pass the Sort in the
     * [Pageable]. Used by the admin list controller, where clicking
     * a column header must actually change the ordering.
     */
    fun findByCrawlConfigId(
        crawlConfigId: Long,
        pageable: Pageable
    ): Page<CrawlRunMetrics>

    /**
     * Explicit "most recent first" — used by the REST endpoint and CSV
     * export where the caller doesn't pass a sort and a stable order
     * matters for downstream consumers (parallel-crawl design issue
     * needs the freshest rows at the top).
     */
    fun findByCrawlConfigIdOrderByStartedAtDesc(
        crawlConfigId: Long,
        pageable: Pageable
    ): Page<CrawlRunMetrics>

    fun findAllByOrderByStartedAtDesc(pageable: Pageable): Page<CrawlRunMetrics>

    @Query(
        """
        SELECT COALESCE(SUM(f.size), 0)
        FROM FSFile f
        WHERE f.jobRunId = :jobRunId
        """
    )
    fun sumBytesForJobRun(@Param("jobRunId") jobRunId: Long): Long

    @Query(
        """
        SELECT f.analysisStatus, COUNT(f)
        FROM FSFile f
        WHERE f.jobRunId = :jobRunId
        GROUP BY f.analysisStatus
        """
    )
    fun countFilesByLevelForJobRun(@Param("jobRunId") jobRunId: Long): List<Array<Any?>>
}
