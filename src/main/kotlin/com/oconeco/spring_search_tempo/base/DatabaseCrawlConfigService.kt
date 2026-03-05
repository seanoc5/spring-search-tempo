package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface DatabaseCrawlConfigService {

    fun count(): Long

    fun findAll(filter: String?, pageable: Pageable): Page<CrawlConfigDTO>

    fun findAllEnabled(): List<CrawlConfigDTO>

    fun get(id: Long): CrawlConfigDTO

    fun getByName(name: String, sourceHost: String? = null): CrawlConfigDTO?

    fun create(crawlConfigDTO: CrawlConfigDTO): Long

    fun update(id: Long, crawlConfigDTO: CrawlConfigDTO)

    fun delete(id: Long)

    fun nameExists(name: String, sourceHost: String? = null, excludeId: Long? = null): Boolean

    fun toggleEnabled(id: Long): Boolean

    fun findDistinctSourceHosts(): List<String>

    // Ownership-filtered methods for multi-tenancy
    fun findAllForCurrentUser(filter: String?, pageable: Pageable): Page<CrawlConfigDTO>

    fun findEnabledForCurrentUser(): List<CrawlConfigDTO>

}
