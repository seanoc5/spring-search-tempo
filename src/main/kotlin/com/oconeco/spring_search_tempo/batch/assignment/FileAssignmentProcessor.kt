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
 * Processor that assigns analysis status to files based on pattern matching.
 *
 * Uses the PatternMatchingService to determine the appropriate AnalysisStatus
 * based on file patterns and parent folder status.
 *
 * @param filePatterns Pattern set for file matching
 * @param patternMatchingService Service for pattern matching
 * @param folderRepository Repository for looking up parent folders
 */
class FileAssignmentProcessor(
    private val filePatterns: PatternSet,
    private val filePatternPriority: PatternPriority = PatternPriority(),
    private val patternMatchingService: PatternMatchingService,
    private val folderRepository: FSFolderRepository
) : ItemProcessor<FileAssignmentItem, AssignmentResult> {

    companion object {
        private val log = LoggerFactory.getLogger(FileAssignmentProcessor::class.java)
    }

    // Cache of folder ID -> AnalysisStatus
    private val parentStatusCache = ConcurrentHashMap<Long, AnalysisStatus>()

    override fun process(item: FileAssignmentItem): AssignmentResult? {
        try {
            // Get parent folder status
            val parentStatus = getParentStatus(item.parentFolderId)
                ?: AnalysisStatus.LOCATE // Default if no parent

            // Determine status using pattern matching
            val newStatus = patternMatchingService.determineFileAnalysisStatus(
                path = item.uri,
                filePatterns = filePatterns,
                parentFolderStatus = parentStatus,
                priority = filePatternPriority
            )

            // Determine reason and setBy
            val (reason, setBy) = determineReasonAndSetBy(item.uri, newStatus, parentStatus)

            return AssignmentResult(
                id = item.id,
                entityType = "FILE",
                newStatus = newStatus,
                reason = reason,
                setBy = setBy
            )

        } catch (e: Exception) {
            log.error("Error processing file assignment for {}: {}", item.uri, e.message)
            return null
        }
    }

    /**
     * Get parent folder's analysis status with caching.
     */
    private fun getParentStatus(parentFolderId: Long?): AnalysisStatus? {
        if (parentFolderId == null) return null

        // Check cache first
        parentStatusCache[parentFolderId]?.let { return it }

        // Fall back to database
        val parent = folderRepository.findById(parentFolderId).orElse(null)
        val status = parent?.analysisStatus

        // Cache for future lookups
        if (status != null) {
            parentStatusCache[parentFolderId] = status
        }

        return status
    }

    /**
     * Determine the reason and setBy values based on how the status was assigned.
     */
    private fun determineReasonAndSetBy(
        uri: String,
        newStatus: AnalysisStatus,
        parentStatus: AnalysisStatus
    ): Pair<String, String> {
        // Check if this was a pattern match
        val matchedPattern = findMatchedPattern(uri, newStatus)

        return when {
            matchedPattern != null -> Pair("PATTERN: $matchedPattern", "PATTERN")
            parentStatus == AnalysisStatus.SKIP -> Pair("INHERITED: parent folder is SKIP", "INHERITED")
            // Note: Files don't directly inherit parent's ANALYZE/SEMANTIC (capped at INDEX)
            newStatus == parentStatus || (parentStatus in listOf(AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC) && newStatus == AnalysisStatus.INDEX) ->
                Pair("INHERITED: parent folder ($parentStatus → $newStatus)", "INHERITED")
            else -> Pair("DEFAULT", "DEFAULT")
        }
    }

    /**
     * Find which pattern matched for this status (for audit trail).
     */
    private fun findMatchedPattern(uri: String, status: AnalysisStatus): String? {
        val patterns = when (status) {
            AnalysisStatus.SKIP -> filePatterns.skip
            AnalysisStatus.LOCATE -> filePatterns.locate
            AnalysisStatus.INDEX -> filePatterns.index
            AnalysisStatus.ANALYZE -> filePatterns.analyze
            AnalysisStatus.SEMANTIC -> filePatterns.semantic
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
        parentStatusCache.clear()
    }
}
