package com.oconeco.remotecrawler.pattern

import com.oconeco.remotecrawler.model.AnalysisStatus
import com.oconeco.remotecrawler.model.PatternPriority
import com.oconeco.remotecrawler.model.PatternSet
import org.slf4j.LoggerFactory
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
class PatternMatchingService {
    private val log = LoggerFactory.getLogger(javaClass)
    private val patternCache = ConcurrentHashMap<String, Pattern>()

    /**
     * Determine AnalysisStatus for a folder based on hierarchical matching.
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
                log.debug("Folder {} matched {} pattern", path, status)
                return status
            }
        }

        // No explicit pattern match - inherit from parent (hierarchical)
        val inherited = parentStatus ?: AnalysisStatus.LOCATE
        log.debug("Folder {} inherited status {} from parent", path, inherited)
        return inherited
    }

    /**
     * Determine AnalysisStatus for a file.
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
        // Check file-specific SKIP patterns first
        if (matchesAny(path, filePatterns.skip)) {
            log.debug("File {} matched SKIP pattern", path)
            return AnalysisStatus.SKIP
        }

        // If parent folder is SKIP, file should also be skipped
        if (parentFolderStatus == AnalysisStatus.SKIP) {
            log.debug("File {} skipped due to parent folder status", path)
            return AnalysisStatus.SKIP
        }

        for (status in priority.orderedStatuses().filter { it != AnalysisStatus.SKIP }) {
            if (matchesAny(path, patternsForStatus(filePatterns, status))) {
                log.debug("File {} matched {} pattern", path, status)
                return status
            }
        }

        // No explicit pattern match - inherit from parent folder, but cap at INDEX
        val inherited = when (parentFolderStatus) {
            AnalysisStatus.SKIP -> AnalysisStatus.SKIP
            AnalysisStatus.ANALYZE -> AnalysisStatus.INDEX
            AnalysisStatus.SEMANTIC -> AnalysisStatus.INDEX
            else -> parentFolderStatus
        }
        log.debug("File {} inherited status {} from parent (capped)", path, inherited)
        return inherited
    }

    /**
     * Quick check if a folder path matches SKIP patterns only.
     * Optimized for discovery phase (SKIP_SUBTREE optimization).
     */
    fun matchesSkipPatternOnly(path: String, skipPatterns: List<String>): SkipCheckResult {
        for (pattern in skipPatterns) {
            try {
                if (getCompiledPattern(pattern).matcher(path).matches()) {
                    log.debug("Folder {} matched SKIP pattern: {}", path, pattern)
                    return SkipCheckResult(isSkip = true, matchedPattern = pattern)
                }
            } catch (e: Exception) {
                log.warn("Invalid SKIP regex pattern '{}': {}", pattern, e.message)
            }
        }
        return SkipCheckResult(isSkip = false, matchedPattern = null)
    }

    private fun matchesAny(path: String, patterns: List<String>): Boolean {
        return patterns.any { pattern ->
            try {
                getCompiledPattern(pattern).matcher(path).matches()
            } catch (e: Exception) {
                log.warn("Invalid regex pattern '{}': {}", pattern, e.message)
                false
            }
        }
    }

    private fun getCompiledPattern(pattern: String): Pattern {
        return patternCache.computeIfAbsent(pattern) { Pattern.compile(it) }
    }

    private fun patternsForStatus(patterns: PatternSet, status: AnalysisStatus): List<String> = when (status) {
        AnalysisStatus.SKIP -> patterns.skip
        AnalysisStatus.LOCATE -> patterns.locate
        AnalysisStatus.INDEX -> patterns.index
        AnalysisStatus.ANALYZE -> patterns.analyze
        AnalysisStatus.SEMANTIC -> patterns.semantic
    }

    fun clearCache() {
        patternCache.clear()
        log.info("Pattern cache cleared")
    }

    fun getCacheSize(): Int = patternCache.size
}

/**
 * Result of a SKIP pattern check.
 */
data class SkipCheckResult(
    val isSkip: Boolean,
    val matchedPattern: String?
)
