package com.oconeco.spring_search_tempo.base.service

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
 * 2. For folders: explicit patterns (ANALYZE > INDEX > LOCATE), then inherit from parent
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
        parentStatus: AnalysisStatus?
    ): AnalysisStatus {
        // SKIP has highest priority - persist with SKIP status, no further processing
        if (matchesAny(path, patterns.skip)) {
            logger.debug("Folder {} matched SKIP pattern", path)
            return AnalysisStatus.SKIP
        }

        // Check explicit patterns in priority order: ANALYZE > INDEX > LOCATE
        if (matchesAny(path, patterns.analyze)) {
            logger.debug("Folder {} matched ANALYZE pattern", path)
            return AnalysisStatus.ANALYZE
        }
        if (matchesAny(path, patterns.index)) {
            logger.debug("Folder {} matched INDEX pattern", path)
            return AnalysisStatus.INDEX
        }
        if (matchesAny(path, patterns.locate)) {
            logger.debug("Folder {} matched LOCATE pattern", path)
            return AnalysisStatus.LOCATE
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
        parentFolderStatus: AnalysisStatus
    ): AnalysisStatus {
        // Check file-specific SKIP patterns first
        if (matchesAny(path, filePatterns.skip)) {
            logger.debug("File {} matched SKIP pattern", path)
            return AnalysisStatus.SKIP
        }

        // If parent folder is SKIP, file should also be skipped
        if (parentFolderStatus == AnalysisStatus.SKIP) {
            logger.debug("File {} skipped due to parent folder status", path)
            return AnalysisStatus.SKIP
        }

        // Check explicit file patterns: ANALYZE > INDEX > LOCATE
        if (matchesAny(path, filePatterns.analyze)) {
            logger.debug("File {} matched ANALYZE pattern", path)
            return AnalysisStatus.ANALYZE
        }
        if (matchesAny(path, filePatterns.index)) {
            logger.debug("File {} matched INDEX pattern", path)
            return AnalysisStatus.INDEX
        }
        if (matchesAny(path, filePatterns.locate)) {
            logger.debug("File {} matched LOCATE pattern", path)
            return AnalysisStatus.LOCATE
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
