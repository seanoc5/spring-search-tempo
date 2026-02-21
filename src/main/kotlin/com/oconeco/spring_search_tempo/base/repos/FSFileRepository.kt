package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param


interface FSFileRepository : JpaRepository<FSFile, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<FSFile>

    fun findFirstByFsFolderId(id: Long): FSFile?

    fun existsByUri(uri: String?): Boolean

    fun findByUri(uri: String): FSFile?

    fun findByBodyTextIsNotNull(pageable: Pageable): Page<FSFile>

    /**
     * Find files that need chunking: have bodyText and either never chunked or modified since last chunking.
     * This prevents re-processing already-chunked files on every job run.
     *
     * WARNING: This finds ALL files globally. Prefer findFilesNeedingChunkingByJobRunId for job-scoped queries.
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.bodyText IS NOT NULL
        AND (f.chunkedAt IS NULL OR f.lastUpdated > f.chunkedAt)
    """)
    fun findFilesNeedingChunking(pageable: Pageable): Page<FSFile>

    /**
     * Find files that need chunking for a specific job run.
     * Only returns files that:
     * - Belong to the specified job run (were created/updated in this crawl)
     * - Have bodyText (have content to chunk)
     * - Either never chunked or modified since last chunking
     *
     * This scopes chunking to the current job run only, preventing re-processing
     * of files from other crawl configs.
     *
     * @param jobRunId The job run to find files for
     * @param pageable Pagination parameters
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.jobRunId = :jobRunId
        AND f.bodyText IS NOT NULL
        AND (f.chunkedAt IS NULL OR f.lastUpdated > f.chunkedAt)
    """)
    fun findFilesNeedingChunkingByJobRunId(
        @Param("jobRunId") jobRunId: Long,
        pageable: Pageable
    ): Page<FSFile>

    /**
     * Find all files excluding those with SKIP analysis status.
     * Used by UI to hide skipped items by default.
     */
    fun findByAnalysisStatusNot(analysisStatus: AnalysisStatus, pageable: Pageable): Page<FSFile>

    /**
     * Find files by ID filter, excluding SKIP status.
     */
    fun findByIdAndAnalysisStatusNot(id: Long, analysisStatus: AnalysisStatus, pageable: Pageable): Page<FSFile>

    /**
     * Count files owned by a specific job run.
     */
    fun countByJobRunId(jobRunId: Long): Long

    /**
     * Count all files owned by job runs belonging to a crawl config.
     * Files belong to whichever crawl config last touched them (via job_run_id).
     */
    @Query("""
        SELECT COUNT(f) FROM FSFile f
        WHERE f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
        )
    """)
    fun countByCrawlConfigId(crawlConfigId: Long): Long

    /**
     * Count files by crawl config, excluding SKIP status.
     */
    @Query("""
        SELECT COUNT(f) FROM FSFile f
        WHERE f.analysisStatus <> :excludedStatus
        AND f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
        )
    """)
    fun countByCrawlConfigIdAndAnalysisStatusNot(crawlConfigId: Long, excludedStatus: AnalysisStatus): Long

    /**
     * Find all files owned by job runs belonging to a crawl config.
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
        )
    """)
    fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable): Page<FSFile>

    /**
     * Find files by crawl config, excluding SKIP status.
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.analysisStatus <> :excludedStatus
        AND f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
        )
    """)
    fun findByCrawlConfigIdAndAnalysisStatusNot(
        crawlConfigId: Long,
        excludedStatus: AnalysisStatus,
        pageable: Pageable
    ): Page<FSFile>

    /**
     * Delete all files belonging to a specific crawl config.
     * Must be called after deleting ContentChunks due to foreign key constraints.
     *
     * @param crawlConfigId The crawl config whose files should be deleted
     * @return The number of files deleted
     */
    @Modifying
    @Query("""
        DELETE FROM FSFile f
        WHERE f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
        )
    """)
    fun deleteByCrawlConfigId(@Param("crawlConfigId") crawlConfigId: Long): Int

}
