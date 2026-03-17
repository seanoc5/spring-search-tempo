package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.HostCrawlSession
import org.springframework.data.jpa.repository.JpaRepository

interface HostCrawlSessionRepository : JpaRepository<HostCrawlSession, Long> {
    fun findByJobRunId(jobRunId: Long): HostCrawlSession?
}
