package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface FSFolderService {

    fun count(): Long

    fun findAll(filter: String?, pageable: Pageable, showSkipped: Boolean = false): Page<FSFolderDTO>

    fun `get`(id: Long): FSFolderDTO

    fun create(fSFolderDTO: FSFolderDTO): Long

    fun update(id: Long, fSFolderDTO: FSFolderDTO)

    fun delete(id: Long)

    fun uriExists(uri: String?): Boolean

    fun getFSFolderValues(): Map<Long, Long>

    /**
     * Count folders owned by a specific job run.
     */
    fun countByJobRunId(jobRunId: Long): Long

    /**
     * Count folders owned by job runs belonging to a crawl config.
     * @param crawlConfigId The crawl config ID
     * @param includeSkipped If true, includes SKIP status folders; otherwise excludes them
     */
    fun countByCrawlConfigId(crawlConfigId: Long, includeSkipped: Boolean = false): Long

    /**
     * Find folders owned by job runs belonging to a crawl config.
     * @param crawlConfigId The crawl config ID
     * @param pageable Pagination parameters
     * @param showSkipped If true, includes SKIP status folders; otherwise excludes them
     */
    fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable, showSkipped: Boolean = false): Page<FSFolderDTO>

    /**
     * Get folder counts grouped by crawl config (excluding SKIP).
     * @return List of pairs [configId, configName, count], ordered by count desc
     */
    fun countByCrawlConfigFacets(): List<Triple<Long, String, Long>>

    /**
     * Get SKIP folder counts grouped by crawl config.
     * @return Map of configId to skip count
     */
    fun countSkippedByCrawlConfig(): Map<Long, Long>

    /**
     * Get folder counts grouped by Status (processing state).
     * @return Map of status name to count
     */
    fun countByStatus(): Map<String, Long>

    /**
     * Get folder counts grouped by AnalysisStatus (processing level).
     * @return Map of analysis status name to count
     */
    fun countByAnalysisStatus(): Map<String, Long>

}
