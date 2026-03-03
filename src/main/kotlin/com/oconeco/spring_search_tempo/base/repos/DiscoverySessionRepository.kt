package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.DiscoverySession
import com.oconeco.spring_search_tempo.base.domain.DiscoveryStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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
}
