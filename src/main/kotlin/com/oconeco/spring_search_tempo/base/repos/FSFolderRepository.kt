package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.domain.Status
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

    fun findByUriIn(uris: Collection<String>): List<FSFolder>

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
     * Find folders by URI substring.
     */
    fun findByUriContainingIgnoreCase(uri: String, pageable: Pageable): Page<FSFolder>

    /**
     * Find folders by URI substring, excluding SKIP status.
     */
    fun findByUriContainingIgnoreCaseAndAnalysisStatusNot(
        uri: String,
        analysisStatus: AnalysisStatus,
        pageable: Pageable
    ): Page<FSFolder>

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

    /**
     * Get folder counts grouped by crawl config.
     * Returns pairs of [crawlConfigId, count].
     */
    @Query("""
        SELECT jr.crawlConfig.id, COUNT(f)
        FROM FSFolder f
        JOIN JobRun jr ON f.jobRunId = jr.id
        WHERE f.analysisStatus <> :excludedStatus
        GROUP BY jr.crawlConfig.id
        ORDER BY COUNT(f) DESC
    """)
    fun countGroupedByCrawlConfig(@Param("excludedStatus") excludedStatus: AnalysisStatus): List<Array<Any>>

    /**
     * Get total folder counts grouped by crawl config for the given config IDs.
     * Returns pairs of [crawlConfigId, count].
     */
    @Query("""
        SELECT jr.crawlConfig.id, COUNT(f)
        FROM FSFolder f
        JOIN JobRun jr ON f.jobRunId = jr.id
        WHERE jr.crawlConfig.id IN :configIds
        GROUP BY jr.crawlConfig.id
    """)
    fun countTotalGroupedByCrawlConfigIds(@Param("configIds") configIds: Collection<Long>): List<Array<Any>>

    /**
     * Get SKIP folder counts grouped by crawl config.
     * Returns pairs of [crawlConfigId, count].
     */
    @Query("""
        SELECT jr.crawlConfig.id, COUNT(f)
        FROM FSFolder f
        JOIN JobRun jr ON f.jobRunId = jr.id
        WHERE f.analysisStatus = :status
        GROUP BY jr.crawlConfig.id
    """)
    fun countSkippedGroupedByCrawlConfig(@Param("status") status: AnalysisStatus): List<Array<Any>>

    /**
     * Find all folder URIs belonging to a crawl config.
     * Efficient query for set comparison in crawl review.
     */
    @Query("""
        SELECT f.uri FROM FSFolder f
        WHERE f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :configId
        )
    """)
    fun findAllUrisByCrawlConfigId(@Param("configId") configId: Long): List<String>

    /**
     * Find all folders (with status info) belonging to a crawl config.
     * Used for detailed comparison in crawl review.
     */
    @Query("""
        SELECT f FROM FSFolder f
        WHERE f.jobRunId IN (
            SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :configId
        )
    """)
    fun findAllByCrawlConfigId(@Param("configId") configId: Long): List<FSFolder>

    /**
     * Compute dense dashboard metrics for a set of folders.
     * Returns rows of:
     * [folderId, directFolderCount, recursiveFolderCount, directFileCount, recursiveFileCount, totalFileSize].
     */
    @Query(
        value = """
            WITH parents AS (
                SELECT
                    p.id AS folder_id,
                    CASE WHEN p.uri = '/' THEN '/' ELSE p.uri || '/' END AS prefix,
                    LENGTH(CASE WHEN p.uri = '/' THEN '/' ELSE p.uri || '/' END) AS prefix_len
                FROM fsfolder p
                WHERE p.id IN (:folderIds)
            )
            SELECT
                p.folder_id,
                COALESCE(fc.direct_folder_count, 0) AS direct_folder_count,
                COALESCE(fc.recursive_folder_count, 0) AS recursive_folder_count,
                COALESCE(ff.direct_file_count, 0) AS direct_file_count,
                COALESCE(ff.recursive_file_count, 0) AS recursive_file_count,
                COALESCE(ff.total_file_size, 0) AS total_file_size
            FROM parents p
            LEFT JOIN LATERAL (
                SELECT
                    COUNT(*) FILTER (
                        WHERE c.id <> p.folder_id
                          AND POSITION('/' IN SUBSTRING(c.uri FROM p.prefix_len + 1)) = 0
                    ) AS direct_folder_count,
                    COUNT(*) FILTER (WHERE c.id <> p.folder_id) AS recursive_folder_count
                FROM fsfolder c
                WHERE c.uri >= p.prefix
                  AND c.uri < p.prefix || chr(1114111)
            ) fc ON TRUE
            LEFT JOIN LATERAL (
                SELECT
                    COUNT(*) FILTER (
                        WHERE POSITION('/' IN SUBSTRING(f.uri FROM p.prefix_len + 1)) = 0
                    ) AS direct_file_count,
                    COUNT(*) AS recursive_file_count,
                    COALESCE(SUM(f.size), 0) AS total_file_size
                FROM fsfile f
                WHERE f.uri >= p.prefix
                  AND f.uri < p.prefix || chr(1114111)
            ) ff ON TRUE
        """,
        nativeQuery = true
    )
    fun findDashboardFolderMetrics(@Param("folderIds") folderIds: Collection<Long>): List<Array<Any?>>

    /**
     * Find immediate child folders by parent URI prefix.
     * The parentUri should end with '/' for proper prefix matching.
     */
    @Query("""
        SELECT f FROM FSFolder f
        WHERE f.uri LIKE :parentUri || '%'
        AND f.uri NOT LIKE :parentUri || '%/%'
    """)
    fun findImmediateChildFolders(@Param("parentUri") parentUri: String): List<FSFolder>

    /**
     * Count folders by processing status.
     */
    fun countByStatus(status: Status): Long

    /**
     * Count folders grouped by processing status.
     * Returns rows of [status, count].
     */
    @Query("""
        SELECT f.status, COUNT(f)
        FROM FSFolder f
        GROUP BY f.status
    """)
    fun countGroupedByStatus(): List<Array<Any?>>

    /**
     * Count folders by analysis status.
     */
    fun countByAnalysisStatus(analysisStatus: AnalysisStatus): Long

    /**
     * Count folders grouped by analysis status.
     * Returns rows of [analysisStatus, count].
     */
    @Query("""
        SELECT f.analysisStatus, COUNT(f)
        FROM FSFolder f
        GROUP BY f.analysisStatus
    """)
    fun countGroupedByAnalysisStatus(): List<Array<Any?>>

    /**
     * Find folders needing analysis status assignment.
     * Returns folders where analysisStatusSetBy is DEFAULT or null.
     * Ordered by URI to ensure parents are processed before children.
     */
    @Query("""
        SELECT f FROM FSFolder f
        WHERE f.analysisStatusSetBy = 'DEFAULT'
        OR f.analysisStatusSetBy IS NULL
        ORDER BY f.uri
    """)
    fun findFoldersNeedingAssignment(pageable: Pageable): Page<FSFolder>

    /**
     * Find a folder's parent by deriving parent URI from the folder's URI.
     * Used for inheritance during assignment.
     */
    @Query(
        value = """
            SELECT f.*
            FROM fsfolder f
            WHERE f.uri = regexp_replace(:childUri, '/[^/]+$', '')
            LIMIT 1
        """,
        nativeQuery = true
    )
    fun findParentByChildUri(@Param("childUri") childUri: String): FSFolder?

}
