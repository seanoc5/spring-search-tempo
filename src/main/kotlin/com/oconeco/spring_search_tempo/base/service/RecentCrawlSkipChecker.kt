package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of checking if a folder should be skipped due to recent crawl by another config.
 */
sealed class RecentCrawlCheckResult {
    /** Folder was not recently crawled by another config - process normally */
    data object NotRecentlyCrawled : RecentCrawlCheckResult()

    /** Folder is a crawl config root that was recently crawled by another config - skip subtree */
    data class SkipSubtree(
        val otherCrawlConfigId: Long,
        val otherAnalysisStatus: AnalysisStatus,
        val lastUpdated: OffsetDateTime
    ) : RecentCrawlCheckResult()
}

/**
 * Service to check if folders should be skipped because they were recently
 * crawled by another crawl configuration.
 *
 * Key behaviors:
 * - Only checks at crawl config root directories (startPaths from other configs)
 * - Never skips folders crawled by the same config (allows aggressive re-crawls)
 * - Caches lookups within a crawl job to minimize DB queries
 *
 * @param fsFolderRepository Repository for folder lookups
 * @param currentCrawlConfigId The ID of the crawl config currently running
 * @param freshnessHours Hours threshold for "recent" crawl (default 24)
 */
class RecentCrawlSkipChecker(
    private val fsFolderRepository: FSFolderRepository,
    private val currentCrawlConfigId: Long,
    private val freshnessHours: Int = 24
) {
    companion object {
        private val log = LoggerFactory.getLogger(RecentCrawlSkipChecker::class.java)
    }

    // Cache: folder URI -> check result (to avoid repeated DB queries)
    private val cache = ConcurrentHashMap<String, RecentCrawlCheckResult>()

    // Threshold time for "recent" crawl
    private val threshold: OffsetDateTime = OffsetDateTime.now().minusHours(freshnessHours.toLong())

    /**
     * Check if a folder should be skipped because it's a crawl config root
     * that was recently crawled by another config.
     *
     * @param path The folder path to check
     * @return RecentCrawlCheckResult indicating whether to skip or continue
     */
    fun shouldSkipFolder(path: Path): RecentCrawlCheckResult {
        val uri = path.toString()

        // Check cache first
        cache[uri]?.let { cachedResult ->
            log.trace("Cache hit for folder: {} -> {}", uri, cachedResult::class.simpleName)
            return cachedResult
        }

        // Check if this folder is a crawl config root that was recently crawled
        val result = checkForRecentCrawlConfigRoot(uri)

        // Cache the result
        cache[uri] = result

        return result
    }

    /**
     * Check if a folder is a crawl config root that was recently crawled by another config.
     */
    private fun checkForRecentCrawlConfigRoot(uri: String): RecentCrawlCheckResult {
        try {
            val recentInfo = fsFolderRepository.findRecentCrawlConfigRootInfo(uri, threshold)
            if (recentInfo == null || recentInfo.isEmpty()) {
                return RecentCrawlCheckResult.NotRecentlyCrawled
            }

            // Native query returns Object[] - handle type conversion
            val otherCrawlConfigId = when (val id = recentInfo[0]) {
                is Long -> id
                is Number -> id.toLong()
                else -> return RecentCrawlCheckResult.NotRecentlyCrawled
            }

            // Never skip our own config's crawls
            if (otherCrawlConfigId == currentCrawlConfigId) {
                log.debug("Folder is our own config root, not skipping: {}", uri)
                return RecentCrawlCheckResult.NotRecentlyCrawled
            }

            // Native query returns enum as String
            val analysisStatus = when (val status = recentInfo[1]) {
                is AnalysisStatus -> status
                is String -> try { AnalysisStatus.valueOf(status) } catch (e: Exception) { AnalysisStatus.LOCATE }
                else -> AnalysisStatus.LOCATE
            }

            val lastUpdated = when (val timestamp = recentInfo[2]) {
                is OffsetDateTime -> timestamp
                is java.sql.Timestamp -> timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC)
                else -> OffsetDateTime.now()
            }

            log.info(
                "Folder is a crawl config root recently crawled by another config: " +
                    "uri={}, otherConfigId={}, status={}, lastUpdated={}",
                uri, otherCrawlConfigId, analysisStatus, lastUpdated
            )

            return RecentCrawlCheckResult.SkipSubtree(
                otherCrawlConfigId = otherCrawlConfigId,
                otherAnalysisStatus = analysisStatus,
                lastUpdated = lastUpdated
            )

        } catch (e: Exception) {
            log.warn("Error checking recent crawl for folder: {} - {}", uri, e.message)
            return RecentCrawlCheckResult.NotRecentlyCrawled
        }
    }

    /**
     * Clear the cache. Useful for testing or between job runs.
     */
    fun clearCache() {
        cache.clear()
        log.debug("Cleared recent crawl check cache")
    }

    /**
     * Get cache statistics for monitoring.
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "cacheSize" to cache.size,
            "currentCrawlConfigId" to currentCrawlConfigId,
            "freshnessHours" to freshnessHours,
            "threshold" to threshold
        )
    }
}
