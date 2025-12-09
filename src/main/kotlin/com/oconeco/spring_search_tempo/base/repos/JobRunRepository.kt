package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.JobRun
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface JobRunRepository : JpaRepository<JobRun, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<JobRun>

    fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable): Page<JobRun>

    fun findByCrawlConfigId(crawlConfigId: Long): List<JobRun>

    fun findByRunStatus(runStatus: RunStatus, pageable: Pageable): Page<JobRun>

    fun findByJobName(jobName: String, pageable: Pageable): Page<JobRun>

    fun findFirstByCrawlConfigIdOrderByStartTimeDesc(crawlConfigId: Long): JobRun?

    fun findFirstByOrderByStartTimeDesc(): JobRun?

}
