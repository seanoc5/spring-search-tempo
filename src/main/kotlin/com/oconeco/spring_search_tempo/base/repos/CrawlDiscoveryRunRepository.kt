package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.CrawlDiscoveryRun
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying

interface CrawlDiscoveryRunRepository : JpaRepository<CrawlDiscoveryRun, Long> {
    fun findByJobRunId(jobRunId: Long): CrawlDiscoveryRun?
    fun findTop10ByCrawlConfigIdAndHostOrderByStartedAtDesc(crawlConfigId: Long, host: String): List<CrawlDiscoveryRun>
    @Modifying
    fun deleteByCrawlConfigId(crawlConfigId: Long): Int
}
