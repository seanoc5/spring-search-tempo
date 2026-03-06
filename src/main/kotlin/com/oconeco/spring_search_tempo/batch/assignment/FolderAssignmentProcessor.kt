package com.oconeco.spring_search_tempo.batch.assignment

import com.oconeco.spring_search_tempo.base.config.PatternPriority
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.util.concurrent.ConcurrentHashMap

/**
 * Processor that assigns analysis status to folders based on pattern matching.
 *
 * Uses the PatternMatchingService to determine the appropriate AnalysisStatus
 * based on folder patterns. Maintains a cache of parent statuses to enable
 * proper inheritance.
 *
 * @param folderPatterns Pattern set for folder matching
 * @param patternMatchingService Service for pattern matching
 * @param folderRepository Repository for looking up parent folders
 */
class FolderAssignmentProcessor(
    private val folderPatterns: PatternSet,
    private val folderPatternPriority: PatternPriority = PatternPriority(),
    private val patternMatchingService: PatternMatchingService,
    private val folderRepository: FSFolderRepository
) : ItemProcessor<FolderAssignmentItem, AssignmentResult> {

    companion object {
        private val log = LoggerFactory.getLogger(FolderAssignmentProcessor::class.java)
    }

    // Cache of folder URI -> AnalysisStatus for inheritance
    private val statusCache = ConcurrentHashMap<String, AnalysisStatus>()

    override fun process(item: FolderAssignmentItem): AssignmentResult? {
        try {
            // Get parent status for inheritance
            val parentStatus = getParentStatus(item.parentUri)

            // Determine status using pattern matching
            val newStatus = patternMatchingService.determineFolderAnalysisStatus(
                path = item.uri,
                patterns = folderPatterns,
                parentStatus = parentStatus,
                priority = folderPatternPriority
            )

            // Determine reason and setBy
            val (reason, setBy) = determineReasonAndSetBy(item.uri, newStatus, parentStatus)

            // Cache this folder's status for children
            statusCache[item.uri] = newStatus

            return AssignmentResult(
                id = item.id,
                entityType = "FOLDER",
                newStatus = newStatus,
                reason = reason,
                setBy = setBy
            )

        } catch (e: Exception) {
            log.error("Error processing folder assignment for {}: {}", item.uri, e.message)
            return null
        }
    }

    /**
     * Get parent folder's analysis status.
     * First checks cache, then falls back to database lookup.
     */
    private fun getParentStatus(parentUri: String?): AnalysisStatus? {
        if (parentUri == null) return null

        // Check cache first
        statusCache[parentUri]?.let { return it }

        // Fall back to database
        val parent = folderRepository.findByUri(parentUri)
        val status = parent?.analysisStatus

        // Cache for future lookups
        if (status != null) {
            statusCache[parentUri] = status
        }

        return status
    }

    /**
     * Determine the reason and setBy values based on how the status was assigned.
     */
    private fun determineReasonAndSetBy(
        uri: String,
        newStatus: AnalysisStatus,
        parentStatus: AnalysisStatus?
    ): Pair<String, String> {
        // Check if this was a pattern match
        val matchedPattern = findMatchedPattern(uri, newStatus)

        return if (matchedPattern != null) {
            Pair("PATTERN: $matchedPattern", "PATTERN")
        } else if (parentStatus != null && newStatus == parentStatus) {
            Pair("INHERITED: parent folder", "INHERITED")
        } else {
            Pair("DEFAULT", "DEFAULT")
        }
    }

    /**
     * Find which pattern matched for this status (for audit trail).
     */
    private fun findMatchedPattern(uri: String, status: AnalysisStatus): String? {
        val patterns = when (status) {
            AnalysisStatus.SKIP -> folderPatterns.skip
            AnalysisStatus.LOCATE -> folderPatterns.locate
            AnalysisStatus.INDEX -> folderPatterns.index
            AnalysisStatus.ANALYZE -> folderPatterns.analyze
            AnalysisStatus.SEMANTIC -> folderPatterns.semantic
        }

        return patterns.firstOrNull { pattern ->
            try {
                Regex(pattern).matches(uri)
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Clear the status cache (for testing or memory management).
     */
    fun clearCache() {
        statusCache.clear()
    }

    /**
     * Get cache size (for monitoring).
     */
    fun getCacheSize(): Int = statusCache.size
}
