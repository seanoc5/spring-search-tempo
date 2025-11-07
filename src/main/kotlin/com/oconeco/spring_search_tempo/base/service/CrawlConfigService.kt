package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.config.CrawlConfiguration
import com.oconeco.spring_search_tempo.base.config.CrawlDefaults
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import org.springframework.stereotype.Service

/**
 * Service interface for accessing crawl configuration.
 * Provides module-safe access to crawl definitions and settings.
 */
interface CrawlConfigService {
    /**
     * Get all crawl definitions.
     */
    fun getAllCrawls(): List<CrawlDefinition>

    /**
     * Get enabled crawl definitions only.
     */
    fun getEnabledCrawls(): List<CrawlDefinition>

    /**
     * Get a crawl definition by name.
     */
    fun getCrawlByName(name: String): CrawlDefinition?

    /**
     * Get global defaults.
     */
    fun getDefaults(): CrawlDefaults

    /**
     * Get effective patterns for a crawl (merged with defaults).
     */
    fun getEffectivePatterns(crawl: CrawlDefinition): EffectivePatterns
}

@Service
class CrawlConfigServiceImpl(
    private val crawlConfiguration: CrawlConfiguration
) : CrawlConfigService {

    override fun getAllCrawls(): List<CrawlDefinition> {
        return crawlConfiguration.crawls
    }

    override fun getEnabledCrawls(): List<CrawlDefinition> {
        return crawlConfiguration.crawls.filter { it.enabled }
    }

    override fun getCrawlByName(name: String): CrawlDefinition? {
        return crawlConfiguration.crawls.firstOrNull { it.name == name }
    }

    override fun getDefaults(): CrawlDefaults {
        return crawlConfiguration.defaults
    }

    override fun getEffectivePatterns(crawl: CrawlDefinition): EffectivePatterns {
        return crawlConfiguration.getEffectivePatterns(crawl)
    }
}
