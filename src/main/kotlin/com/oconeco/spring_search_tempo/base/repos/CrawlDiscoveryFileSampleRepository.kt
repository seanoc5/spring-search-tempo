package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.CrawlDiscoveryFileSample
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface CrawlDiscoveryFileSampleRepository : JpaRepository<CrawlDiscoveryFileSample, Long> {

    @Modifying
    @Query("DELETE FROM CrawlDiscoveryFileSample s WHERE s.folderObservation.id = :folderObservationId")
    fun deleteByFolderObservationId(folderObservationId: Long): Int

    fun findByFolderObservationIdInOrderByFolderObservationIdAscSampleSlotAsc(
        folderObservationIds: Collection<Long>
    ): List<CrawlDiscoveryFileSample>
}
