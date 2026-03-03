package com.oconeco.remotecrawler.pattern

import com.oconeco.remotecrawler.model.AnalysisStatus
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
        parentStatus: AnalysisStatus?
    ): AnalysisStatus {
        // SKIP has highest priority
        if (matchesAny(path, patterns.skip)) {
            log.debug("Folder {} matched SKIP pattern", path)
            return AnalysisStatus.SKIP
        }

        // Check explicit patterns in priority order: SEMANTIC > ANALYZE > INDEX > LOCATE
        if (matchesAny(path, patterns.semantic)) {
            log.debug("Folder {} matched SEMANTIC pattern", path)
            return AnalysisStatus.SEMANTIC
        }
        if (matchesAny(path, patterns.analyze)) {
            log.debug("Folder {} matched ANALYZE pattern", path)
            return AnalysisStatus.ANALYZE
        }
        if (matchesAny(path, patterns.index)) {
            log.debug("Folder {} matched INDEX pattern", path)
            return AnalysisStatus.INDEX
        }
        if (matchesAny(path, patterns.locate)) {
            log.debug("Folder {} matched LOCATE pattern", path)
            return AnalysisStatus.LOCATE
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
        parentFolderStatus: AnalysisStatus
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

        // Check explicit file patterns: SEMANTIC > ANALYZE > INDEX > LOCATE
        if (matchesAny(path, filePatterns.semantic)) {
            log.debug("File {} matched SEMANTIC pattern", path)
            return AnalysisStatus.SEMANTIC
        }
        if (matchesAny(path, filePatterns.analyze)) {
            log.debug("File {} matched ANALYZE pattern", path)
            return AnalysisStatus.ANALYZE
        }
        if (matchesAny(path, filePatterns.index)) {
            log.debug("File {} matched INDEX pattern", path)
            return AnalysisStatus.INDEX
        }
        if (matchesAny(path, filePatterns.locate)) {
            log.debug("File {} matched LOCATE pattern", path)
            return AnalysisStatus.LOCATE
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
