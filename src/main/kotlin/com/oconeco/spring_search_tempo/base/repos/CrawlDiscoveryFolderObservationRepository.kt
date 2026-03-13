package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.CrawlDiscoveryFolderObservation
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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

    @Query("SELECT o.id FROM CrawlDiscoveryFolderObservation o WHERE o.crawlConfig.id = :crawlConfigId")
    fun findIdsByCrawlConfigId(@Param("crawlConfigId") crawlConfigId: Long): List<Long>

    @Modifying
    @Query("DELETE FROM CrawlDiscoveryFolderObservation o WHERE o.crawlConfig.id = :crawlConfigId")
    fun deleteByCrawlConfigId(@Param("crawlConfigId") crawlConfigId: Long): Int
}
