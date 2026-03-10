package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.CrawlTemperature
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Service for smart crawl scheduling based on folder temperature.
 *
 * Folder temperature reflects modification activity:
 * - HOT: Frequently changing folders (modified in last N days or high change score)
 * - WARM: Moderately active folders (modified in last M days)
 * - COLD: Rarely changing folders (no changes in M+ days)
 *
 * The crawl scheduler uses temperature to prioritize:
 * - HOT folders: Include in every crawl session
 * - WARM folders: Include once daily
 * - COLD folders: Include weekly
 */
@Service
class CrawlSchedulingService(
    private val fsFolderRepository: FSFolderRepository
) {
    companion object {
        private val log = LoggerFactory.getLogger(CrawlSchedulingService::class.java)

        // Default thresholds
        const val DEFAULT_HOT_THRESHOLD_DAYS = 7
        const val DEFAULT_WARM_THRESHOLD_DAYS = 30

        // Change score thresholds
        const val HOT_SCORE_THRESHOLD = 50
        const val WARM_SCORE_THRESHOLD = 20

        // Change score adjustments
        const val CHANGE_DETECTED_BONUS = 10
        const val NO_CHANGE_PENALTY = 1
        const val MAX_CHANGE_SCORE = 100
        const val MIN_CHANGE_SCORE = 0
    }

    /**
     * Get folders due for crawling based on temperature and last crawl time.
     *
     * @param sourceHost Host to query folders for
     * @param hotThresholdDays Days threshold for HOT temperature
     * @param warmThresholdDays Days threshold for WARM temperature
     * @return List of folders due for crawling, prioritized by temperature
     */
    fun getFoldersDueForCrawl(
        sourceHost: String,
        hotThresholdDays: Int = DEFAULT_HOT_THRESHOLD_DAYS,
        warmThresholdDays: Int = DEFAULT_WARM_THRESHOLD_DAYS
    ): List<FolderCrawlPriority> {
        val now = OffsetDateTime.now()

        // HOT folders: always include if not crawled in last 4 hours
        val hotFolders = fsFolderRepository.findFoldersDueByTemperature(
            sourceHost = sourceHost,
            temperature = CrawlTemperature.HOT,
            notCrawledSince = now.minusHours(4)
        )

        // WARM folders: include if not crawled today
        val warmFolders = fsFolderRepository.findFoldersDueByTemperature(
            sourceHost = sourceHost,
            temperature = CrawlTemperature.WARM,
            notCrawledSince = now.minusDays(1)
        )

        // COLD folders: include if not crawled this week
        val coldFolders = fsFolderRepository.findFoldersDueByTemperature(
            sourceHost = sourceHost,
            temperature = CrawlTemperature.COLD,
            notCrawledSince = now.minusDays(7)
        )

        log.debug(
            "Folders due for crawl on {}: HOT={}, WARM={}, COLD={}",
            sourceHost, hotFolders.size, warmFolders.size, coldFolders.size
        )

        return buildPrioritizedList(hotFolders, warmFolders, coldFolders)
    }

    /**
     * Update folder temperature after crawl based on detected changes.
     *
     * @param folderId ID of the folder to update
     * @param changesDetected Whether any file changes were detected in this folder
     * @param mostRecentChildMtime Most recent child file modification time, if known
     * @param hotThresholdDays Days threshold for HOT temperature
     * @param warmThresholdDays Days threshold for WARM temperature
     */
    @Transactional
    fun updateTemperatureAfterCrawl(
        folderId: Long,
        changesDetected: Boolean,
        mostRecentChildMtime: OffsetDateTime? = null,
        hotThresholdDays: Int = DEFAULT_HOT_THRESHOLD_DAYS,
        warmThresholdDays: Int = DEFAULT_WARM_THRESHOLD_DAYS
    ) {
        val folder = fsFolderRepository.findById(folderId).orElse(null) ?: run {
            log.warn("Cannot update temperature: folder {} not found", folderId)
            return
        }

        val oldTemperature = folder.crawlTemperature
        val oldScore = folder.changeScore

        // Update change score
        folder.changeScore = if (changesDetected) {
            (folder.changeScore + CHANGE_DETECTED_BONUS).coerceAtMost(MAX_CHANGE_SCORE)
        } else {
            (folder.changeScore - NO_CHANGE_PENALTY).coerceAtLeast(MIN_CHANGE_SCORE)
        }

        // Update child modified timestamp if provided
        if (mostRecentChildMtime != null) {
            val current = folder.childModifiedAt
            if (current == null || mostRecentChildMtime.isAfter(current)) {
                folder.childModifiedAt = mostRecentChildMtime
            }
        }

        // Recalculate temperature
        folder.crawlTemperature = calculateTemperature(
            childModifiedAt = folder.childModifiedAt,
            changeScore = folder.changeScore,
            hotThresholdDays = hotThresholdDays,
            warmThresholdDays = warmThresholdDays
        )

        folder.lastCrawledAt = OffsetDateTime.now()
        fsFolderRepository.save(folder)

        if (folder.crawlTemperature != oldTemperature) {
            log.debug(
                "Folder {} temperature changed: {} -> {} (score: {} -> {})",
                folderId, oldTemperature, folder.crawlTemperature, oldScore, folder.changeScore
            )
        }
    }

    /**
     * Bulk update temperatures after a crawl session completes.
     * More efficient than individual updates for large batches.
     *
     * @param folderChanges Map of folderId to whether changes were detected
     * @param hotThresholdDays Days threshold for HOT temperature
     * @param warmThresholdDays Days threshold for WARM temperature
     */
    @Transactional
    fun updateTemperaturesAfterCrawl(
        folderChanges: Map<Long, Boolean>,
        hotThresholdDays: Int = DEFAULT_HOT_THRESHOLD_DAYS,
        warmThresholdDays: Int = DEFAULT_WARM_THRESHOLD_DAYS
    ) {
        if (folderChanges.isEmpty()) return

        val folders = fsFolderRepository.findAllById(folderChanges.keys)
        val now = OffsetDateTime.now()

        for (folder in folders) {
            val folderId = folder.id ?: continue
            val changesDetected = folderChanges[folderId] ?: false

            folder.changeScore = if (changesDetected) {
                (folder.changeScore + CHANGE_DETECTED_BONUS).coerceAtMost(MAX_CHANGE_SCORE)
            } else {
                (folder.changeScore - NO_CHANGE_PENALTY).coerceAtLeast(MIN_CHANGE_SCORE)
            }

            folder.crawlTemperature = calculateTemperature(
                childModifiedAt = folder.childModifiedAt,
                changeScore = folder.changeScore,
                hotThresholdDays = hotThresholdDays,
                warmThresholdDays = warmThresholdDays
            )

            folder.lastCrawledAt = now
        }

        fsFolderRepository.saveAll(folders)
        log.debug("Updated temperatures for {} folders", folders.size)
    }

    /**
     * Calculate temperature based on modification recency and change score.
     */
    fun calculateTemperature(
        childModifiedAt: OffsetDateTime?,
        changeScore: Int,
        hotThresholdDays: Int = DEFAULT_HOT_THRESHOLD_DAYS,
        warmThresholdDays: Int = DEFAULT_WARM_THRESHOLD_DAYS
    ): CrawlTemperature {
        val now = OffsetDateTime.now()
        val daysSinceChange = childModifiedAt?.let {
            ChronoUnit.DAYS.between(it, now)
        } ?: Long.MAX_VALUE

        return when {
            // HOT: recently modified OR consistently active
            daysSinceChange <= hotThresholdDays || changeScore >= HOT_SCORE_THRESHOLD -> CrawlTemperature.HOT
            // WARM: modified within threshold OR moderately active
            daysSinceChange <= warmThresholdDays || changeScore >= WARM_SCORE_THRESHOLD -> CrawlTemperature.WARM
            // COLD: old and inactive
            else -> CrawlTemperature.COLD
        }
    }

    /**
     * Build a prioritized list from temperature-grouped folders.
     * HOT folders come first, then WARM, then COLD.
     * Within each group, folders are ordered by change score descending.
     */
    private fun buildPrioritizedList(
        hotFolders: List<FSFolder>,
        warmFolders: List<FSFolder>,
        coldFolders: List<FSFolder>
    ): List<FolderCrawlPriority> {
        val result = mutableListOf<FolderCrawlPriority>()

        var priority = 1
        for (folder in hotFolders) {
            result.add(toFolderCrawlPriority(folder, priority++))
        }
        for (folder in warmFolders) {
            result.add(toFolderCrawlPriority(folder, priority++))
        }
        for (folder in coldFolders) {
            result.add(toFolderCrawlPriority(folder, priority++))
        }

        return result
    }

    private fun toFolderCrawlPriority(folder: FSFolder, priority: Int): FolderCrawlPriority {
        return FolderCrawlPriority(
            folderId = folder.id!!,
            uri = folder.uri!!,
            temperature = folder.crawlTemperature,
            priority = priority,
            changeScore = folder.changeScore,
            lastCrawledAt = folder.lastCrawledAt,
            childModifiedAt = folder.childModifiedAt
        )
    }
}

/**
 * Folder with crawl priority information.
 */
data class FolderCrawlPriority(
    val folderId: Long,
    val uri: String,
    val temperature: CrawlTemperature,
    val priority: Int,
    val changeScore: Int,
    val lastCrawledAt: OffsetDateTime?,
    val childModifiedAt: OffsetDateTime?
)
