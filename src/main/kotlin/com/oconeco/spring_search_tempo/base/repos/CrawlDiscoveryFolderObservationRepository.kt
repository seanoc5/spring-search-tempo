package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.CrawlDiscoveryFolderObservation
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CrawlDiscoveryFolderObservationRepository : JpaRepository<CrawlDiscoveryFolderObservation, Long> {
    fun findByCrawlConfigIdAndHostAndPath(
        crawlConfigId: Long,
        host: String,
        path: String
    ): CrawlDiscoveryFolderObservation?

    fun findByCrawlConfigIdAndHostAndPathIn(
        crawlConfigId: Long,
        host: String,
        paths: Collection<String>
    ): List<CrawlDiscoveryFolderObservation>

    fun findByCrawlConfigIdAndHost(crawlConfigId: Long, host: String): List<CrawlDiscoveryFolderObservation>

    fun findByCrawlConfigIdAndHostOrderByPathAsc(crawlConfigId: Long, host: String): List<CrawlDiscoveryFolderObservation>

    fun findByCrawlConfigIdAndHost(
        crawlConfigId: Long,
        host: String,
        pageable: Pageable
    ): Page<CrawlDiscoveryFolderObservation>

    fun findByCrawlConfigIdAndHostAndPathStartingWith(
        crawlConfigId: Long,
        host: String,
        pathPrefix: String,
        pageable: Pageable
    ): Page<CrawlDiscoveryFolderObservation>
}
