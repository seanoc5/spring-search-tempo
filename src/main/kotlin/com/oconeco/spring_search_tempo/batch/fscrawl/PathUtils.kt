package com.oconeco.spring_search_tempo.batch.fscrawl

import java.nio.file.Path

/**
 * Utility functions for path operations in crawl processing.
 */
object PathUtils {

    /**
     * Find the matching start path for a given path using longest prefix match.
     *
     * When crawling multiple start paths, files/folders need to know which start path
     * they belong to for depth calculation. This function finds the start path that
     * is an ancestor of the given path, preferring the longest (most specific) match.
     *
     * Example:
     * ```
     * startPaths = ["/opt", "/opt/work", "/home/user"]
     * path = "/opt/work/project/file.kt"
     * result = "/opt/work"  // longest match
     * ```
     *
     * @param path The file or folder path to match
     * @param startPaths List of possible start paths
     * @return The matching start path, or null if no match found
     */
    fun findMatchingStartPath(path: Path, startPaths: List<Path>): Path? {
        if (startPaths.isEmpty()) {
            return null
        }

        // If only one start path, use it directly
        if (startPaths.size == 1) {
            val startPath = startPaths[0]
            return if (path.startsWith(startPath)) startPath else null
        }

        // Find all matching start paths (paths that are ancestors of the given path)
        val matches = startPaths.filter { startPath ->
            path.startsWith(startPath)
        }

        // Return the longest match (most specific)
        // This handles cases where one start path is a subdirectory of another
        // e.g., startPaths = ["/opt", "/opt/work"], path = "/opt/work/file.kt"
        // We want "/opt/work" not "/opt"
        return matches.maxByOrNull { it.nameCount }
    }

    /**
     * Calculate crawl depth for a path relative to its matching start path.
     *
     * This function combines start path matching with depth calculation.
     *
     * @param path The file or folder path
     * @param startPaths List of possible start paths
     * @return Depth relative to matching start path, or 0 if no match
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
