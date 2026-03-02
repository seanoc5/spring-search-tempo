package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.HostNameHolder
import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

@Service
class DatabaseCrawlConfigServiceImpl(
    private val crawlConfigRepository: CrawlConfigRepository,
    private val crawlConfigMapper: CrawlConfigMapper
) : DatabaseCrawlConfigService {

    override fun count(): Long = crawlConfigRepository.count()

    override fun findAll(filter: String?, pageable: Pageable): Page<CrawlConfigDTO> {
        val page: Page<CrawlConfig> = if (filter != null) {
            val filterId = filter.toLongOrNull()
            crawlConfigRepository.findAllById(filterId, pageable)
        } else {
            crawlConfigRepository.findAll(pageable)
        }
        return PageImpl(
            page.content.map { crawlConfig ->
                crawlConfigMapper.updateCrawlConfigDTO(crawlConfig, CrawlConfigDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun findAllEnabled(): List<CrawlConfigDTO> {
        return crawlConfigRepository.findByEnabled(true)
            .map { crawlConfig ->
                crawlConfigMapper.updateCrawlConfigDTO(crawlConfig, CrawlConfigDTO())
            }
    }

    override fun get(id: Long): CrawlConfigDTO = crawlConfigRepository.findById(id)
        .map { crawlConfig ->
            crawlConfigMapper.updateCrawlConfigDTO(crawlConfig, CrawlConfigDTO())
        }
        .orElseThrow { NotFoundException() }

    override fun getByName(name: String): CrawlConfigDTO? {
        val crawlConfig = crawlConfigRepository.findByName(name) ?: return null
        return crawlConfigMapper.updateCrawlConfigDTO(crawlConfig, CrawlConfigDTO())
    }

    override fun create(crawlConfigDTO: CrawlConfigDTO): Long {
        val crawlConfig = CrawlConfig()
        crawlConfigMapper.updateCrawlConfig(crawlConfigDTO, crawlConfig)
        return crawlConfigRepository.save(crawlConfig).id!!
    }

    override fun update(id: Long, crawlConfigDTO: CrawlConfigDTO) {
        val crawlConfig = crawlConfigRepository.findById(id)
            .orElseThrow { NotFoundException() }
        crawlConfigMapper.updateCrawlConfig(crawlConfigDTO, crawlConfig)
        crawlConfigRepository.save(crawlConfig)
    }

    override fun delete(id: Long) {
        val crawlConfig = crawlConfigRepository.findById(id)
            .orElseThrow { NotFoundException() }
        crawlConfigRepository.delete(crawlConfig)
    }

    override fun nameExists(name: String): Boolean = crawlConfigRepository.existsByName(name)

    override fun toggleEnabled(id: Long): Boolean {
        val crawlConfig = crawlConfigRepository.findById(id)
            .orElseThrow { NotFoundException() }
        crawlConfig.enabled = !crawlConfig.enabled
        crawlConfigRepository.save(crawlConfig)
        return crawlConfig.enabled
    }

    override fun findAllEnabledForHost(host: String): List<CrawlConfigDTO> {
        val normalizedHost = host.trim()
        return crawlConfigRepository.findByEnabled(true)
            .filter { config ->
                val target = config.targetHost?.trim()
                target.isNullOrBlank() || target.equals(normalizedHost, ignoreCase = true)
            }
            .map { crawlConfigMapper.updateCrawlConfigDTO(it, CrawlConfigDTO()) }
    }

    override fun findAllEnabledForCurrentHost(): List<CrawlConfigDTO> {
        return findAllEnabledForHost(HostNameHolder.currentHostName)
    }

    override fun findDistinctTargetHosts(): List<String> {
        return crawlConfigRepository.findDistinctTargetHosts()
    }

    override fun isForHost(config: CrawlConfigDTO, host: String): Boolean {
        val target = config.targetHost?.trim()
        return target.isNullOrBlank() || target.equals(host.trim(), ignoreCase = true)
    }

    override fun isForCurrentHost(config: CrawlConfigDTO): Boolean {
        return isForHost(config, HostNameHolder.currentHostName)
    }

}
