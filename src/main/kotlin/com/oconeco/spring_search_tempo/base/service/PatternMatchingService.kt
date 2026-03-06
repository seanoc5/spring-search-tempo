package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.config.PatternPriority
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Service for determining AnalysisStatus based on hierarchical pattern matching.
 *
 * Pattern matching follows these rules:
 * 1. SKIP patterns have highest priority (matched items persisted with SKIP status, no further processing)
 * 2. For folders: explicit patterns (SEMANTIC > ANALYZE > INDEX > LOCATE), then inherit from parent
 * 3. For files: explicit patterns, then inherit from parent folder (capped at INDEX for safety)
 * 4. Hierarchical: folder's AnalysisStatus affects all children unless overridden
 * 5. SKIP folders: children are not crawled at all (processing stops)
 */
@Service
class PatternMatchingService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val patternCache = ConcurrentHashMap<String, Pattern>()

    /**
     * Determine AnalysisStatus for a folder based on hierarchical matching.
     * Parent folder's status affects this determination unless explicit patterns match.
     *
     * @param path The folder path to evaluate
     * @param patterns Pattern set for folder matching
     * @param parentStatus The parent folder's AnalysisStatus (null for root)
     * @return The determined AnalysisStatus
     */
    fun determineFolderAnalysisStatus(
        path: String,
        patterns: PatternSet,
        parentStatus: AnalysisStatus?,
        priority: PatternPriority = PatternPriority()
    ): AnalysisStatus {
        for (status in priority.orderedStatuses()) {
            if (matchesAny(path, patternsForStatus(patterns, status))) {
                logger.debug("Folder {} matched {} pattern", path, status)
                return status
            }
        }

        // No explicit pattern match - inherit from parent (hierarchical)
        val inherited = parentStatus ?: AnalysisStatus.LOCATE
        logger.debug("Folder {} inherited status {} from parent", path, inherited)
        return inherited
    }

    /**
     * Determine AnalysisStatus for a file.
     * Considers both file-specific patterns and parent folder status.
     *
     * @param path The file path to evaluate
     * @param filePatterns Pattern set for file matching
     * @param parentFolderStatus The parent folder's AnalysisStatus
     * @return The determined AnalysisStatus
     */
    fun determineFileAnalysisStatus(
        path: String,
        filePatterns: PatternSet,
        parentFolderStatus: AnalysisStatus,
        priority: PatternPriority = PatternPriority()
    ): AnalysisStatus {
        // Check file-specific SKIP patterns first.
        if (matchesAny(path, filePatterns.skip)) {
            logger.debug("File {} matched SKIP pattern", path)
            return AnalysisStatus.SKIP
        }

        // If parent folder is SKIP, file should also be skipped
        if (parentFolderStatus == AnalysisStatus.SKIP) {
            logger.debug("File {} skipped due to parent folder status", path)
            return AnalysisStatus.SKIP
        }

        // Check explicit file patterns by configured priority (excluding SKIP, already handled).
        for (status in priority.orderedStatuses().filter { it != AnalysisStatus.SKIP }) {
            if (matchesAny(path, patternsForStatus(filePatterns, status))) {
                logger.debug("File {} matched {} pattern", path, status)
                return status
            }
        }

        // No explicit pattern match - inherit from parent folder, but cap at INDEX
        // This prevents automatic ANALYZE of all files in an ANALYZE folder
        val inherited = when (parentFolderStatus) {
            AnalysisStatus.SKIP -> AnalysisStatus.SKIP
            AnalysisStatus.ANALYZE -> AnalysisStatus.INDEX  // Cap at INDEX for safety
            AnalysisStatus.SEMANTIC -> AnalysisStatus.INDEX  // Cap at INDEX for safety
            else -> parentFolderStatus
        }
        logger.debug("File {} inherited status {} from parent (capped)", path, inherited)
        return inherited
    }

    /**
     * Quick check if a folder path matches SKIP patterns only.
     *
     * This method is optimized for the discovery phase where we only need to know
     * if a folder should be skipped (SKIP_SUBTREE optimization). It avoids the
     * overhead of checking all pattern types.
     *
     * Performance critical: Called during filesystem enumeration for every folder.
     *
     * @param path The folder path to check
     * @param skipPatterns List of SKIP regex patterns
     * @return SkipCheckResult containing match info
     */
    fun matchesSkipPatternOnly(path: String, skipPatterns: List<String>): SkipCheckResult {
        for (pattern in skipPatterns) {
            try {
                if (getCompiledPattern(pattern).matcher(path).matches()) {
                    logger.debug("Folder {} matched SKIP pattern: {}", path, pattern)
                    return SkipCheckResult(isSkip = true, matchedPattern = pattern)
                }
            } catch (e: Exception) {
                logger.warn("Invalid SKIP regex pattern '{}': {}", pattern, e.message)
            }
        }
        return SkipCheckResult(isSkip = false, matchedPattern = null)
    }

    /**
     * Check if a path matches any pattern in the list.
     *
     * @param path The path to match
     * @param patterns List of regex patterns
     * @return true if any pattern matches
     */
    private fun matchesAny(path: String, patterns: List<String>): Boolean {
        return patterns.any { pattern ->
            try {
                getCompiledPattern(pattern).matcher(path).matches()
            } catch (e: Exception) {
                logger.warn("Invalid regex pattern '{}': {}", pattern, e.message)
                false
            }
        }
    }

    /**
     * Get a compiled pattern from cache, or compile and cache it.
     *
     * @param pattern The regex pattern string
     * @return Compiled Pattern object
     */
    private fun getCompiledPattern(pattern: String): Pattern {
        return patternCache.computeIfAbsent(pattern) {
            Pattern.compile(it)
        }
    }

    private fun patternsForStatus(patterns: PatternSet, status: AnalysisStatus): List<String> = when (status) {
        AnalysisStatus.SKIP -> patterns.skip
        AnalysisStatus.LOCATE -> patterns.locate
        AnalysisStatus.INDEX -> patterns.index
        AnalysisStatus.ANALYZE -> patterns.analyze
        AnalysisStatus.SEMANTIC -> patterns.semantic
    }

    /**
     * Clear the pattern cache. Useful for testing or if patterns change at runtime.
     */
    fun clearCache() {
        patternCache.clear()
        logger.info("Pattern cache cleared")
    }

    /**
     * Get current cache size for monitoring.
     */
    fun getCacheSize(): Int = patternCache.size
}

/**
 * Result of a SKIP pattern check.
 * Contains both the match result and the pattern that matched (for audit trail).
 */
data class SkipCheckResult(
    /** True if the path matched a SKIP pattern */
    val isSkip: Boolean,
    /** The pattern that matched, for analysisStatusReason field */
    val matchedPattern: String?
)
