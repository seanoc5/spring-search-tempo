package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.JobRun
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface JobRunRepository : JpaRepository<JobRun, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<JobRun>

    /**
     * Find all job runs with CrawlConfig eagerly fetched.
     * Avoids LazyInitializationException when accessing crawlConfig properties.
     */
    @Query("""
        SELECT jr FROM JobRun jr
        LEFT JOIN FETCH jr.crawlConfig
    """,
        countQuery = "SELECT COUNT(jr) FROM JobRun jr"
    )
    fun findAllWithCrawlConfig(pageable: Pageable): Page<JobRun>

    /**
     * Find job runs by ID filter with CrawlConfig eagerly fetched.
     */
    @Query("""
        SELECT jr FROM JobRun jr
        LEFT JOIN FETCH jr.crawlConfig
        WHERE jr.id = :id
    """,
        countQuery = "SELECT COUNT(jr) FROM JobRun jr WHERE jr.id = :id"
    )
    fun findByIdWithCrawlConfig(id: Long?, pageable: Pageable): Page<JobRun>

    fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable): Page<JobRun>

    fun findByCrawlConfigId(crawlConfigId: Long): List<JobRun>

    fun findByRunStatus(runStatus: RunStatus, pageable: Pageable): Page<JobRun>

    fun findByJobName(jobName: String, pageable: Pageable): Page<JobRun>

    fun findFirstByCrawlConfigIdOrderByStartTimeDesc(crawlConfigId: Long): JobRun?

    fun findFirstByOrderByStartTimeDesc(): JobRun?

}
