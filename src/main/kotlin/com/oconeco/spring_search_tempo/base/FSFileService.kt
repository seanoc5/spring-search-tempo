package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface FSFileService {

    fun count(): Long

    fun findAll(filter: String?, pageable: Pageable, showSkipped: Boolean = false): Page<FSFileDTO>

    fun `get`(id: Long): FSFileDTO

    fun create(fSFileDTO: FSFileDTO): Long

    fun update(id: Long, fSFileDTO: FSFileDTO)

    fun delete(id: Long)

    fun uriExists(uri: String?): Boolean

    fun getFSFileValues(): Map<Long, Long>

    /**
     * Find files with non-null bodyText for chunking.
     * Used by batch processing to retrieve files that need text chunking.
     * @deprecated Use findFilesNeedingChunking instead to avoid re-processing.
     */
    fun findFilesWithBodyText(pageable: Pageable): Page<FSFileDTO>

    /**
     * Find files that need chunking: have bodyText and either never chunked or modified since last chunking.
     * This prevents re-processing already-chunked files on every job run.
     *
     * WARNING: This finds ALL files globally. Prefer findFilesNeedingChunkingByJobRunId for job-scoped queries.
     */
    fun findFilesNeedingChunking(pageable: Pageable): Page<FSFileDTO>

    /**
     * Find files that need chunking for a specific job run.
     * Only returns files that belong to the specified job run and need chunking.
     * This scopes chunking to the current job run only.
     *
     * @param jobRunId The job run ID to find files for
     * @param pageable Pagination parameters
     */
    fun findFilesNeedingChunkingByJobRunId(jobRunId: Long, pageable: Pageable): Page<FSFileDTO>

    /**
     * Mark a file as chunked by setting its chunkedAt timestamp.
     */
    fun markAsChunked(fileId: Long)

    /**
     * Count files owned by a specific job run.
     */
    fun countByJobRunId(jobRunId: Long): Long

    /**
     * Count files owned by job runs belonging to a crawl config.
     * @param crawlConfigId The crawl config ID
     * @param includeSkipped If true, includes SKIP status files; otherwise excludes them
     */
    fun countByCrawlConfigId(crawlConfigId: Long, includeSkipped: Boolean = false): Long

    /**
     * Find files owned by job runs belonging to a crawl config.
     * @param crawlConfigId The crawl config ID
     * @param pageable Pagination parameters
     * @param showSkipped If true, includes SKIP status files; otherwise excludes them
     */
    fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable, showSkipped: Boolean = false): Page<FSFileDTO>

    /**
     * Get file counts grouped by analysis status.
     * @return Map of AnalysisStatus name to count
     */
    fun countByAnalysisStatus(): Map<String, Long>

    /**
     * Get file counts grouped by crawl config (excluding SKIP).
     * @return List of pairs [configId, configName, count], ordered by count desc
     */
    fun countByCrawlConfigFacets(): List<Triple<Long, String, Long>>

    /**
     * Get SKIP file counts grouped by crawl config.
     * @return Map of configId to skip count
     */
    fun countSkippedByCrawlConfig(): Map<Long, Long>

    /**
     * Get file counts grouped by Status (processing state).
     * @return Map of status name to count
     */
    fun countByStatus(): Map<String, Long>

    /**
     * Get searchable file counts (INDEX, ANALYZE, SEMANTIC) grouped by crawl config.
     * @return Map of crawlConfigId to count
     */
    fun countSearchableByCrawlConfig(): Map<Long, Long>

}
