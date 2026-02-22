package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime


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

    /**
     * Find recent crawl info for a folder by URI.
     * Returns [crawlConfigId, analysisStatus, lastUpdated] if the folder exists
     * and was updated after the threshold time.
     *
     * Used by overlapping crawl detection to skip subtrees already crawled
     * by another config within the freshness window.
     */
    @Query("""
        SELECT jr.crawlConfig.id, f.analysisStatus, f.lastUpdated
        FROM FSFolder f
        JOIN JobRun jr ON f.jobRunId = jr.id
        WHERE f.uri = :uri
        AND f.lastUpdated >= :threshold
    """)
    fun findRecentCrawlInfo(
        @Param("uri") uri: String,
        @Param("threshold") threshold: OffsetDateTime
    ): Array<Any?>?

    /**
     * Check if a folder is a crawl config root that was recently crawled.
     * Used to detect when a parent crawl encounters a child crawl's start path.
     *
     * Returns [crawlConfigId, analysisStatus, lastUpdated] if this folder URI matches
     * a crawl config's start path and was crawled by that config within the threshold.
     *
     * Uses native SQL because PostgreSQL array ANY() syntax is not supported in JPQL.
     */
    @Query(
        value = """
            SELECT cc.id, f.analysis_status, f.last_updated
            FROM fsfolder f
            JOIN job_run jr ON f.job_run_id = jr.id
            JOIN crawl_config cc ON jr.crawl_config_id = cc.id
            WHERE f.uri = :uri
            AND f.last_updated >= :threshold
            AND :uri = ANY(cc.start_paths)
        """,
        nativeQuery = true
    )
    fun findRecentCrawlConfigRootInfo(
        @Param("uri") uri: String,
        @Param("threshold") threshold: OffsetDateTime
    ): Array<Any?>?

}
