package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.UserOwnershipService
import com.oconeco.spring_search_tempo.base.config.HostNameHolder
import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
class DatabaseCrawlConfigServiceImpl(
    private val crawlConfigRepository: CrawlConfigRepository,
    private val crawlConfigMapper: CrawlConfigMapper,
    private val userOwnershipService: UserOwnershipService
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

    override fun getByName(name: String, sourceHost: String?): CrawlConfigDTO? {
        val normalizedName = normalizeName(name)
        val normalizedHost = normalizeSourceHost(sourceHost)
        val crawlConfig = crawlConfigRepository
            .findByNameIgnoreCaseAndSourceHostIgnoreCase(normalizedName, normalizedHost)
            ?: return null
        return crawlConfigMapper.updateCrawlConfigDTO(crawlConfig, CrawlConfigDTO())
    }

    override fun create(crawlConfigDTO: CrawlConfigDTO): Long {
        normalizeForPersist(crawlConfigDTO)
        if (nameExists(crawlConfigDTO.name!!, crawlConfigDTO.sourceHost, excludeId = null)) {
            throw IllegalArgumentException(
                "Configuration '${crawlConfigDTO.name}' already exists for host '${crawlConfigDTO.sourceHost}'"
            )
        }

        val crawlConfig = CrawlConfig()
        crawlConfigMapper.updateCrawlConfig(crawlConfigDTO, crawlConfig)
        // New DTOs may not carry an explicit version from forms/wizard.
        if (crawlConfig.version == null) {
            crawlConfig.version = 0L
        }
        return try {
            crawlConfigRepository.save(crawlConfig).id!!
        } catch (ex: DataIntegrityViolationException) {
            throw IllegalArgumentException(
                "Configuration '${crawlConfigDTO.name}' already exists for host '${crawlConfigDTO.sourceHost}'",
                ex
            )
        }
    }

    override fun update(id: Long, crawlConfigDTO: CrawlConfigDTO) {
        val crawlConfig = crawlConfigRepository.findById(id)
            .orElseThrow { NotFoundException() }

        normalizeForPersist(crawlConfigDTO)
        if (nameExists(crawlConfigDTO.name!!, crawlConfigDTO.sourceHost, excludeId = id)) {
            throw IllegalArgumentException(
                "Configuration '${crawlConfigDTO.name}' already exists for host '${crawlConfigDTO.sourceHost}'"
            )
        }

        crawlConfigMapper.updateCrawlConfig(crawlConfigDTO, crawlConfig)
        try {
            crawlConfigRepository.save(crawlConfig)
        } catch (ex: DataIntegrityViolationException) {
            throw IllegalArgumentException(
                "Configuration '${crawlConfigDTO.name}' already exists for host '${crawlConfigDTO.sourceHost}'",
                ex
            )
        }
    }

    override fun delete(id: Long) {
        val crawlConfig = crawlConfigRepository.findById(id)
            .orElseThrow { NotFoundException() }
        crawlConfigRepository.delete(crawlConfig)
    }

    override fun nameExists(name: String, sourceHost: String?, excludeId: Long?): Boolean {
        val normalizedName = normalizeName(name)
        val normalizedHost = normalizeSourceHost(sourceHost)
        return if (excludeId == null) {
            crawlConfigRepository.existsByNameIgnoreCaseAndSourceHostIgnoreCase(normalizedName, normalizedHost)
        } else {
            crawlConfigRepository.existsByNameIgnoreCaseAndSourceHostIgnoreCaseAndIdNot(
                normalizedName,
                normalizedHost,
                excludeId
            )
        }
    }

    override fun toggleEnabled(id: Long): Boolean {
        val crawlConfig = crawlConfigRepository.findById(id)
            .orElseThrow { NotFoundException() }
        crawlConfig.enabled = !crawlConfig.enabled
        crawlConfigRepository.save(crawlConfig)
        return crawlConfig.enabled
    }

    override fun findDistinctSourceHosts(): List<String> {
        return crawlConfigRepository.findDistinctSourceHosts()
    }

    override fun findAllForCurrentUser(filter: String?, pageable: Pageable): Page<CrawlConfigDTO> {
        var ownedSourceHosts = userOwnershipService.getCurrentUserSourceHosts()

        // If user has no owned sourceHosts, fall back to current server's host
        // This ensures users see something even before explicit host assignments
        if (ownedSourceHosts.isEmpty()) {
            ownedSourceHosts = listOf(HostNameHolder.currentHostName)
        }

        val page = crawlConfigRepository.findBySourceHostIn(ownedSourceHosts, pageable)
        return PageImpl(
            page.content.map { crawlConfig ->
                crawlConfigMapper.updateCrawlConfigDTO(crawlConfig, CrawlConfigDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun findEnabledForCurrentUser(): List<CrawlConfigDTO> {
        var ownedSourceHosts = userOwnershipService.getCurrentUserSourceHosts()

        // If user has no owned sourceHosts, fall back to current server's host
        if (ownedSourceHosts.isEmpty()) {
            ownedSourceHosts = listOf(HostNameHolder.currentHostName)
        }

        return crawlConfigRepository.findByEnabledAndSourceHostIn(true, ownedSourceHosts)
            .map { crawlConfig ->
                crawlConfigMapper.updateCrawlConfigDTO(crawlConfig, CrawlConfigDTO())
            }
    }

    private fun normalizeForPersist(crawlConfigDTO: CrawlConfigDTO) {
        val normalizedName = normalizeName(crawlConfigDTO.name)
        val normalizedSourceHost = normalizeSourceHost(crawlConfigDTO.sourceHost)

        crawlConfigDTO.name = normalizedName
        crawlConfigDTO.sourceHost = normalizedSourceHost
        crawlConfigDTO.label = crawlConfigDTO.label?.trim()?.ifBlank { null } ?: normalizedName
        crawlConfigDTO.type = crawlConfigDTO.type?.trim()?.ifBlank { null } ?: "CRAWL_CONFIG"
        crawlConfigDTO.uri = buildCanonicalUri(normalizedSourceHost, normalizedName)
    }

    private fun normalizeName(name: String?): String =
        name?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Configuration name is required")

    private fun normalizeSourceHost(sourceHost: String?): String =
        sourceHost?.trim()?.takeIf { it.isNotBlank() } ?: HostNameHolder.currentHostName

    private fun buildCanonicalUri(sourceHost: String, name: String): String {
        val hostSlug = slugify(sourceHost)
        val nameSlug = slugify(name)
        return "tempo:crawl-config:$hostSlug/$nameSlug"
    }

    private fun slugify(value: String): String {
        val slug = value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifBlank { "default" }
    }

}
