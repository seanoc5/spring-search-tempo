package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CrawlConfigRepository : JpaRepository<CrawlConfig, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<CrawlConfig>

    fun findByName(name: String): CrawlConfig?

    fun findByEnabled(enabled: Boolean, pageable: Pageable): Page<CrawlConfig>

    fun findByEnabled(enabled: Boolean): List<CrawlConfig>

    fun existsByName(name: String): Boolean

}
