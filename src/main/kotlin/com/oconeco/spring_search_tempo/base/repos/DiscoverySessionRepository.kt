package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.DiscoverySession
import com.oconeco.spring_search_tempo.base.domain.DiscoveryStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface DiscoverySessionRepository : JpaRepository<DiscoverySession, Long> {

    fun findByHost(host: String): List<DiscoverySession>

    fun findByHostAndStatus(host: String, status: DiscoveryStatus): List<DiscoverySession>

    @Query("SELECT s FROM DiscoverySession s WHERE s.host = :host ORDER BY s.dateCreated DESC")
    fun findByHostOrderByDateCreatedDesc(host: String): List<DiscoverySession>

    @Query("SELECT s FROM DiscoverySession s WHERE s.status = :status ORDER BY s.dateCreated DESC")
    fun findByStatusOrderByDateCreatedDesc(status: DiscoveryStatus): List<DiscoverySession>

    @Query("""
        SELECT s FROM DiscoverySession s
        LEFT JOIN FETCH s.folders
        WHERE s.id = :id
    """)
    fun findByIdWithFolders(id: Long): Optional<DiscoverySession>

    @Query("SELECT s FROM DiscoverySession s WHERE s.crawlConfig.id = :crawlConfigId ORDER BY s.appliedAt DESC")
    fun findByCrawlConfigIdOrderByAppliedAtDesc(crawlConfigId: Long): List<DiscoverySession>

    @Query(
        """
        SELECT s FROM DiscoverySession s
        WHERE s.crawlConfig.id = :crawlConfigId
        ORDER BY s.lastUpdated DESC, s.dateCreated DESC
        """
    )
    fun findByCrawlConfigIdOrderByLastUpdatedDesc(crawlConfigId: Long): List<DiscoverySession>

    @Modifying
    @Query("UPDATE DiscoverySession s SET s.crawlConfig = NULL WHERE s.crawlConfig.id = :crawlConfigId")
    fun clearCrawlConfigReference(@Param("crawlConfigId") crawlConfigId: Long): Int
}
