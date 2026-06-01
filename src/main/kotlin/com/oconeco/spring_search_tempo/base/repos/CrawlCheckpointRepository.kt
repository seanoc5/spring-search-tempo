package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.CrawlCheckpoint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface CrawlCheckpointRepository : JpaRepository<CrawlCheckpoint, Long> {

    fun findByCrawlConfigId(crawlConfigId: Long): CrawlCheckpoint?

    @Modifying
    @Query("DELETE FROM CrawlCheckpoint c WHERE c.crawlConfigId = :crawlConfigId")
    fun deleteByCrawlConfigId(crawlConfigId: Long): Int
}
