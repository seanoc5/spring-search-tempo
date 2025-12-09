package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.model.JobRunDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.OffsetDateTime

interface JobRunService {

    fun findAll(filter: String?, pageable: Pageable): Page<JobRunDTO>

    fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable): Page<JobRunDTO>

    fun get(id: Long): JobRunDTO

    fun create(jobRunDTO: JobRunDTO): Long

    fun update(id: Long, jobRunDTO: JobRunDTO)

    fun delete(id: Long)

    fun getLatestRunForConfig(crawlConfigId: Long): JobRunDTO?

    fun getLatestRun(): JobRunDTO?

    /**
     * Create a new job run for a crawl configuration.
     */
    fun startJobRun(crawlConfigId: Long, jobName: String): JobRunDTO

    /**
     * Update job run statistics.
     */
    fun updateJobRunStats(
        jobRunId: Long,
        filesDiscovered: Long? = null,
        filesNew: Long? = null,
        filesUpdated: Long? = null,
        filesSkipped: Long? = null,
        filesError: Long? = null,
        foldersDiscovered: Long? = null,
        foldersNew: Long? = null,
        foldersUpdated: Long? = null,
        foldersSkipped: Long? = null
    )

    /**
     * Complete a job run.
     */
    fun completeJobRun(jobRunId: Long, runStatus: RunStatus, errorMessage: String? = null)

}
