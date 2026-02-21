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

}
