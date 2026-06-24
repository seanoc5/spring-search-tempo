package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.MetadataDuplicateGroup
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime


interface FSFileRepository : JpaRepository<FSFile, Long>, FSFileMetadataDuplicateRepository {

    @Query("""
        SELECT f FROM FSFile f
        WHERE f.sourceHostRef IS NULL
        AND (f.sourceHost IS NULL OR TRIM(f.sourceHost) = '')
        ORDER BY f.id
    """)
    fun findOrphanSourceHostFiles(pageable: Pageable): Page<FSFile>

    fun findAllById(id: Long?, pageable: Pageable): Page<FSFile>

    fun findFirstByFsFolderId(id: Long): FSFile?

    /**
     * Count files directly owned by a folder whose analysisStatus is in the given set.
     * Used by the folder NLP rollup panel to decide whether enough indexed content
     * exists to render the panel.
     */
    fun countByFsFolderIdAndAnalysisStatusIn(
        folderId: Long,
        analysisStatuses: Collection<AnalysisStatus>
    ): Long

    fun existsByUri(uri: String?): Boolean

    fun findByUri(uri: String): FSFile?

    fun findByUriIn(uris: Collection<String>): List<FSFile>

    @Query("""
        SELECT f.id FROM FSFile f
        WHERE f.uri LIKE CONCAT(:escapedPrefix, '%') ESCAPE '\'
        ORDER BY LENGTH(f.uri) DESC
    """)
    fun findIdsByUriPrefix(
        @Param("escapedPrefix") escapedPrefix: String
    ): List<Long>

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
     * Count all files owned by a crawl config.
     */
    fun countByCrawlConfigId(crawlConfigId: Long): Long

    /**
     * Count files by crawl config, excluding SKIP status.
     */
    fun countByCrawlConfigIdAndAnalysisStatusNot(crawlConfigId: Long, excludedStatus: AnalysisStatus): Long

    /**
     * Find all files owned by a crawl config.
     */
    fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable): Page<FSFile>

    /**
     * Find files by crawl config, excluding SKIP status.
     */
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
    fun deleteByCrawlConfigId(crawlConfigId: Long): Int

    @Modifying
    fun deleteBySourceHost(sourceHost: String): Int

    /**
     * Find all per-entry archive rows whose parent is the given archive URI.
     * Used during incremental re-crawl to remove stale entries when an archive
     * changes (issue #118).
     */
    fun findByParentArchiveUri(parentArchiveUri: String): List<FSFile>

    /**
     * Delete all per-entry archive rows belonging to the given archive (issue #118).
     */
    @Modifying
    fun deleteByParentArchiveUri(parentArchiveUri: String): Int

    /**
     * Count per-entry archive rows for an archive URI (issue #118).
     */
    fun countByParentArchiveUri(parentArchiveUri: String): Long

    /**
     * Count files by analysis status.
     */
    fun countByAnalysisStatus(analysisStatus: AnalysisStatus): Long

    /**
     * Count files grouped by processing status.
     * Returns rows of [status, count].
     */
    @Query("""
        SELECT f.status, COUNT(f)
        FROM FSFile f
        GROUP BY f.status
    """)
    fun countGroupedByStatus(): List<Array<Any?>>

    /**
     * Count files grouped by analysis status.
     * Returns rows of [analysisStatus, count].
     */
    @Query("""
        SELECT f.analysisStatus, COUNT(f)
        FROM FSFile f
        GROUP BY f.analysisStatus
    """)
    fun countGroupedByAnalysisStatus(): List<Array<Any?>>

    /**
     * Get file counts grouped by crawl config.
     * Returns pairs of [crawlConfigId, count].
     */
    @Query("""
        SELECT f.crawlConfigId, COUNT(f)
        FROM FSFile f
        WHERE f.analysisStatus <> :excludedStatus
        AND f.crawlConfigId IS NOT NULL
        GROUP BY f.crawlConfigId
        ORDER BY COUNT(f) DESC
    """)
    fun countGroupedByCrawlConfig(@Param("excludedStatus") excludedStatus: AnalysisStatus): List<Array<Any>>

    /**
     * Get total file counts grouped by crawl config for the given config IDs.
     * Returns pairs of [crawlConfigId, count].
     */
    @Query("""
        SELECT f.crawlConfigId, COUNT(f)
        FROM FSFile f
        WHERE f.crawlConfigId IN :configIds
        GROUP BY f.crawlConfigId
    """)
    fun countTotalGroupedByCrawlConfigIds(@Param("configIds") configIds: Collection<Long>): List<Array<Any>>

    /**
     * Get total file size (sum of bytes) grouped by crawl config for the given config IDs.
     * Returns pairs of [crawlConfigId, totalSize].
     */
    @Query("""
        SELECT f.crawlConfigId, COALESCE(SUM(f.size), 0)
        FROM FSFile f
        WHERE f.crawlConfigId IN :configIds
        GROUP BY f.crawlConfigId
    """)
    fun sumSizeGroupedByCrawlConfigIds(@Param("configIds") configIds: Collection<Long>): List<Array<Any>>

    /**
     * Get SKIP file counts grouped by crawl config.
     * Returns pairs of [crawlConfigId, count].
     */
    @Query("""
        SELECT f.crawlConfigId, COUNT(f)
        FROM FSFile f
        WHERE f.analysisStatus = :status
        AND f.crawlConfigId IS NOT NULL
        GROUP BY f.crawlConfigId
    """)
    fun countSkippedGroupedByCrawlConfig(@Param("status") status: AnalysisStatus): List<Array<Any>>

    /**
     * Find all file URIs belonging to a crawl config.
     * Efficient query for set comparison in crawl review.
     */
    @Query("""
        SELECT f.uri FROM FSFile f
        WHERE f.crawlConfigId = :configId
    """)
    fun findAllUrisByCrawlConfigId(@Param("configId") configId: Long): List<String>

    /**
     * Find all files (with status info) belonging to a crawl config.
     * Used for detailed comparison in crawl review.
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.crawlConfigId = :configId
    """)
    fun findAllByCrawlConfigId(@Param("configId") configId: Long): List<FSFile>

    /**
     * Find files for a crawl config under a folder URI prefix (recursive).
     * The folderPrefix should include trailing slash except root.
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.crawlConfigId = :crawlConfigId
        AND f.uri LIKE CONCAT(:folderPrefix, '%')
        ORDER BY f.uri
    """)
    fun findByCrawlConfigIdAndUriPrefix(
        @Param("crawlConfigId") crawlConfigId: Long,
        @Param("folderPrefix") folderPrefix: String
    ): List<FSFile>

    /**
     * Count files for a crawl config under a folder URI prefix (recursive).
     */
    @Query("""
        SELECT COUNT(f) FROM FSFile f
        WHERE f.crawlConfigId = :crawlConfigId
        AND f.uri LIKE CONCAT(:folderPrefix, '%')
    """)
    fun countByCrawlConfigIdAndUriPrefix(
        @Param("crawlConfigId") crawlConfigId: Long,
        @Param("folderPrefix") folderPrefix: String
    ): Long

    /**
     * Find immediate child files by parent folder URI prefix.
     * The folderUri should end with '/' for proper prefix matching.
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.uri LIKE :folderUri || '%'
        AND f.uri NOT LIKE :folderUri || '%/%'
    """)
    fun findImmediateChildFiles(@Param("folderUri") folderUri: String): List<FSFile>

    /**
     * Count files by processing status.
     */
    fun countByStatus(status: Status): Long

    /**
     * Find files needing analysis status assignment.
     * Returns files where analysisStatusSetBy is DEFAULT or null.
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.analysisStatusSetBy = 'DEFAULT'
        OR f.analysisStatusSetBy IS NULL
    """)
    fun findFilesNeedingAssignment(pageable: Pageable): Page<FSFile>

    /**
     * Find files needing indexing (text extraction).
     * Returns files where:
     * - analysisStatus is INDEX, ANALYZE, or SEMANTIC
     * - indexedAt is null (never indexed) OR lastUpdated > indexedAt (modified since)
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.analysisStatus IN :statuses
        AND (f.indexedAt IS NULL OR f.lastUpdated > f.indexedAt)
    """)
    fun findFilesNeedingIndexing(
        @Param("statuses") statuses: List<AnalysisStatus>,
        pageable: Pageable
    ): Page<FSFile>

    /**
     * Count searchable files (INDEX, ANALYZE, SEMANTIC) grouped by crawl config.
     * Returns pairs of [crawlConfigId, count].
     */
    @Query("""
        SELECT f.crawlConfigId, COUNT(f)
        FROM FSFile f
        WHERE f.analysisStatus IN :searchableStatuses
        AND f.crawlConfigId IS NOT NULL
        GROUP BY f.crawlConfigId
    """)
    fun countSearchableGroupedByCrawlConfig(
        @Param("searchableStatuses") searchableStatuses: List<AnalysisStatus>
    ): List<Array<Any>>

    /**
     * Find chunked files whose body_text exceeds the given character threshold.
     * Backfill helper for ADR-006 / issue #147 — these rows can be safely
     * truncated because their full text is already represented in
     * ContentChunk. `LENGTH()` on a PostgreSQL `text` column is a character
     * count, which is what we compare against the threshold.
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.bodyText IS NOT NULL
        AND f.chunkedAt IS NOT NULL
        AND LENGTH(f.bodyText) > :thresholdChars
        ORDER BY f.id
    """)
    fun findChunkedFilesWithLargeBodyText(
        @Param("thresholdChars") thresholdChars: Long,
        pageable: Pageable
    ): Page<FSFile>

    /**
     * Find all files sharing the same SHA-256 content hash.
     * Issue #119: byte-identical duplicate detection.
     */
    fun findByContentHash(contentHash: String): List<FSFile>

    /**
     * Count files matching this hash other than the given id.
     * Used by the FSFile detail UI to render "N duplicate copies" where N
     * excludes the file the user is currently viewing.
     */
    fun countByContentHashAndIdNot(contentHash: String, id: Long): Int

    /**
     * Find sibling version candidates for the smart-diff feature (issue #144):
     * other files sharing the same [label] (typically the basename) but with a
     * different [contentHash], so they are byte-distinct from this file.
     *
     * Files with a null contentHash are excluded — we can only assert "different
     * bytes" once both sides are hashed. Ordering is fsLastModified DESC then
     * id DESC, so the most recently modified sibling renders first in the
     * "Compare with..." dropdown.
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.label = :label
        AND f.id <> :excludeId
        AND f.contentHash IS NOT NULL
        AND f.contentHash <> :excludeContentHash
        ORDER BY f.fsLastModified DESC NULLS LAST, f.id DESC
    """)
    fun findSiblingVersionCandidates(
        @Param("label") label: String,
        @Param("excludeId") excludeId: Long,
        @Param("excludeContentHash") excludeContentHash: String,
        pageable: Pageable,
    ): List<FSFile>

    /**
     * Fetch the actual files for one metadata-duplicate group (issue #120).
     */
    @Query("""
        SELECT f FROM FSFile f
        WHERE f.label = :label
        AND f.size = :size
        AND f.fsLastModified = :fsLastModified
        ORDER BY f.id
    """)
    fun findByMetadataTriple(
        @Param("label") label: String,
        @Param("size") size: Long,
        @Param("fsLastModified") fsLastModified: OffsetDateTime,
    ): List<FSFile>

}
