package com.oconeco.remotecrawler.util

import java.nio.file.Path

/**
 * Utility functions for path operations in crawl processing.
 */
object PathUtils {

    /**
     * Find the matching start path for a given path using longest prefix match.
     *
     * Example:
     * ```
     * startPaths = ["/opt", "/opt/work", "/home/user"]
     * path = "/opt/work/project/file.kt"
     * result = "/opt/work"  // longest match
     * ```
     */
    fun findMatchingStartPath(path: Path, startPaths: List<Path>): Path? {
        if (startPaths.isEmpty()) {
            return null
        }

        if (startPaths.size == 1) {
            val startPath = startPaths[0]
            return if (path.startsWith(startPath)) startPath else null
        }

        val matches = startPaths.filter { startPath -> path.startsWith(startPath) }
        return matches.maxByOrNull { it.nameCount }
    }

    /**
     * Calculate crawl depth for a path relative to its matching start path.
     */
    fun calculateCrawlDepth(path: Path, startPaths: List<Path>): Int {
        val matchingStartPath = findMatchingStartPath(path, startPaths) ?: return 0

        return try {
            val relativePath = matchingStartPath.relativize(path)
            relativePath.nameCount
        } catch (e: Exception) {
            0
        }
    }
}
