package com.oconeco.spring_search_tempo.base.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.config.PatternPriority
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Converts between database CrawlConfig entities and configuration CrawlDefinition objects.
 * Used to create batch jobs from database-stored configurations.
 */
@Service
class CrawlConfigConverter(
    private val objectMapper: ObjectMapper
) {

    companion object {
        private val log = LoggerFactory.getLogger(CrawlConfigConverter::class.java)
    }

    /**
     * Convert a CrawlConfigDTO (from database) to a CrawlDefinition (for job execution).
     */
    fun toDefinition(crawlConfigDTO: CrawlConfigDTO): CrawlDefinition {
        return CrawlDefinition(
            name = crawlConfigDTO.name ?: "unknown",
            label = crawlConfigDTO.label ?: crawlConfigDTO.name ?: "Unknown Crawl",
            startPaths = crawlConfigDTO.startPaths ?: emptyList(),
            maxDepth = crawlConfigDTO.maxDepth,
            followLinks = crawlConfigDTO.followLinks,
            parallel = crawlConfigDTO.parallel,
            folderPatterns = PatternSet(
                skip = parseJsonArray(crawlConfigDTO.folderPatternsSkip),
                locate = parseJsonArray(crawlConfigDTO.folderPatternsLocate),
                index = parseJsonArray(crawlConfigDTO.folderPatternsIndex),
                analyze = parseJsonArray(crawlConfigDTO.folderPatternsAnalyze),
                semantic = parseJsonArray(crawlConfigDTO.folderPatternsSemantic)
            ),
            filePatterns = PatternSet(
                skip = parseJsonArray(crawlConfigDTO.filePatternsSkip),
                locate = parseJsonArray(crawlConfigDTO.filePatternsLocate),
                index = parseJsonArray(crawlConfigDTO.filePatternsIndex),
                analyze = parseJsonArray(crawlConfigDTO.filePatternsAnalyze),
                semantic = parseJsonArray(crawlConfigDTO.filePatternsSemantic)
            ),
            folderPatternPriority = PatternPriority(
                skip = crawlConfigDTO.folderPrioritySkip,
                semantic = crawlConfigDTO.folderPrioritySemantic,
                analyze = crawlConfigDTO.folderPriorityAnalyze,
                index = crawlConfigDTO.folderPriorityIndex,
                locate = crawlConfigDTO.folderPriorityLocate
            ),
            filePatternPriority = PatternPriority(
                skip = crawlConfigDTO.filePrioritySkip,
                semantic = crawlConfigDTO.filePrioritySemantic,
                analyze = crawlConfigDTO.filePriorityAnalyze,
                index = crawlConfigDTO.filePriorityIndex,
                locate = crawlConfigDTO.filePriorityLocate
            )
        )
    }

    /**
     * Parse a JSON array string to a List<String>.
     * Returns empty list if the string is null or cannot be parsed.
     */
    private fun parseJsonArray(jsonString: String?): List<String> {
        if (jsonString.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            objectMapper.readValue(jsonString, object : TypeReference<List<String>>() {})
        } catch (e: Exception) {
            log.error("Failed to parse JSON array: {}", jsonString, e)
            emptyList()
        }
    }

    /**
     * Convert a list of strings to a JSON array string.
     */
    fun toJsonArray(list: List<String>?): String? {
        if (list.isNullOrEmpty()) return null

        return try {
            objectMapper.writeValueAsString(list)
        } catch (e: Exception) {
            log.error("Failed to serialize list to JSON", e)
            null
        }
    }
}
