package com.oconeco.spring_search_tempo.base.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.config.CrawlConfiguration
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Seeds the crawl_config table with configurations from application.yml on startup.
 * Only runs if the table is empty.
 */
@Component
class CrawlConfigDataSeeder(
    private val crawlConfigRepository: CrawlConfigRepository,
    private val crawlConfiguration: CrawlConfiguration,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private val log = LoggerFactory.getLogger(CrawlConfigDataSeeder::class.java)
    }

    @PostConstruct
    @Transactional
    fun seedData() {
        // Check if crawl configs already exist
        val existingCount = crawlConfigRepository.count()
        if (existingCount > 0) {
            log.info("Crawl config table already contains {} entries. Skipping seed.", existingCount)
            return
        }

        log.info("Crawl config table is empty. Seeding from application.yml...")

        val crawlDefinitions = crawlConfiguration.crawls
        if (crawlDefinitions.isEmpty()) {
            log.warn("No crawl definitions found in application.yml. Nothing to seed.")
            return
        }

        var seededCount = 0
        for (crawlDef in crawlDefinitions) {
            try {
                val crawlConfig = convertToEntity(crawlDef)
                crawlConfigRepository.save(crawlConfig)
                seededCount++
                log.debug("Seeded crawl config: {} ({})", crawlDef.name, crawlDef.label)
            } catch (e: Exception) {
                log.error("Failed to seed crawl config: {}", crawlDef.name, e)
            }
        }

        log.info("Successfully seeded {} crawl configurations from application.yml", seededCount)
    }

    /**
     * Convert a CrawlDefinition from application.yml to a CrawlConfig entity.
     */
    private fun convertToEntity(crawlDef: CrawlDefinition): CrawlConfig {
        return CrawlConfig().apply {
            // Generate URI from name
            uri = "crawl-config:${crawlDef.name}"

            // Basic info
            name = crawlDef.name
            displayLabel = crawlDef.label
            enabled = crawlDef.enabled
            status = Status.NEW
            analysisStatus = AnalysisStatus.LOCATE

            // Paths and settings
            startPaths = crawlDef.startPaths.toTypedArray()
            maxDepth = crawlDef.maxDepth
            followLinks = crawlDef.followLinks
            parallel = crawlDef.parallel

            // Convert patterns to JSON strings
            folderPatternsSkip = crawlDef.folderPatterns.skip.toJsonArray()
            folderPatternsLocate = crawlDef.folderPatterns.locate.toJsonArray()
            folderPatternsIndex = crawlDef.folderPatterns.index.toJsonArray()
            folderPatternsAnalyze = crawlDef.folderPatterns.analyze.toJsonArray()

            filePatternsSkip = crawlDef.filePatterns.skip.toJsonArray()
            filePatternsLocate = crawlDef.filePatterns.locate.toJsonArray()
            filePatternsIndex = crawlDef.filePatterns.index.toJsonArray()
            filePatternsAnalyze = crawlDef.filePatterns.analyze.toJsonArray()

            // Metadata
            label = "Crawl Config: ${crawlDef.label}"
            description = "Imported from application.yml on ${OffsetDateTime.now()}"
            version = 1L
            archived = false
        }
    }

    /**
     * Convert a list of strings to a JSON array string.
     */
    private fun List<String>.toJsonArray(): String? {
        if (isEmpty()) return null
        return try {
            objectMapper.writeValueAsString(this)
        } catch (e: Exception) {
            log.error("Failed to serialize pattern list to JSON", e)
            null
        }
    }
}
