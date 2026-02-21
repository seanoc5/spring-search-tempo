package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param


interface FSFolderRepository : JpaRepository<FSFolder, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<FSFolder>

    /**
     * Find all folders with JobRun label via left join.
     * Returns array of [FSFolder, jobRunLabel].
     */
    @Query("""
        SELECT f, jr.label FROM FSFolder f
        LEFT JOIN JobRun jr ON f.jobRunId = jr.id
    """)
    fun findAllWithJobRunLabel(pageable: Pageable): Page<Array<Any?>>

    /**
     * Find folders excluding SKIP status, with JobRun label.
     */
    @Query("""
        SELECT f, jr.label FROM FSFolder f
        LEFT JOIN JobRun jr ON f.jobRunId = jr.id
        WHERE f.analysisStatus <> :excludedStatus
    """)
    fun findByAnalysisStatusNotWithJobRunLabel(
        excludedStatus: AnalysisStatus,
        pageable: Pageable
    ): Page<Array<Any?>>

    fun existsByUri(uri: String?): Boolean

    fun findByUri(uri: String?): FSFolder?

    /**
     * Find all folders excluding those with SKIP analysis status.
     * Used by UI to hide skipped items by default.
     */
    fun findByAnalysisStatusNot(analysisStatus: AnalysisStatus, pageable: Pageable): Page<FSFolder>

    /**
     * Find folders by ID filter, excluding SKIP status.
     */
    fun findByIdAndAnalysisStatusNot(id: Long, analysisStatus: AnalysisStatus, pageable: Pageable): Page<FSFolder>

    /**
     * Count folders owned by a specific job run.
     */
    fun countByJobRunId(jobRunId: Long): Long

    /**
     * Count all folders owned by job runs belonging to a crawl config.
     * Folders belong to whichever crawl config last touched them (via job_run_id).
     */
    @Query("""
        SELECT COUNT(f) FROM FSFolder f
        WHERE f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
        )
    """)
    fun countByCrawlConfigId(crawlConfigId: Long): Long

    /**
     * Count folders by crawl config, excluding SKIP status.
     */
    @Query("""
        SELECT COUNT(f) FROM FSFolder f
        WHERE f.analysisStatus <> :excludedStatus
        AND f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
        )
    """)
    fun countByCrawlConfigIdAndAnalysisStatusNot(crawlConfigId: Long, excludedStatus: AnalysisStatus): Long

    /**
     * Find all folders owned by job runs belonging to a crawl config.
     */
    @Query("""
        SELECT f FROM FSFolder f
        WHERE f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
        )
    """)
    fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable): Page<FSFolder>

    /**
     * Find folders by crawl config, excluding SKIP status.
     */
    @Query("""
        SELECT f FROM FSFolder f
        WHERE f.analysisStatus <> :excludedStatus
        AND f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
        )
    """)
    fun findByCrawlConfigIdAndAnalysisStatusNot(
        crawlConfigId: Long,
        excludedStatus: AnalysisStatus,
        pageable: Pageable
    ): Page<FSFolder>

    /**
     * Delete all folders belonging to a specific crawl config.
     * Must be called after deleting FSFiles due to foreign key constraints.
     *
     * @param crawlConfigId The crawl config whose folders should be deleted
     * @return The number of folders deleted
     */
    @Modifying
    @Query("""
        DELETE FROM FSFolder f
        WHERE f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
        )
    """)
    fun deleteByCrawlConfigId(@Param("crawlConfigId") crawlConfigId: Long): Int

}
