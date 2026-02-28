package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.*
import java.nio.file.Path

/**
 * Service for comparing filesystem state against database state for crawl review.
 * Helps identify what's been crawled vs what's missing, why items are missing
 * (skip patterns vs unexpected gaps), and file type coverage analysis.
 */
interface CrawlReviewService {

    /**
     * Compare folders from filesystem list against DB for a crawl config.
     *
     * @param fsListPath Path to file containing folder paths (one per line)
     * @param crawlConfigId The crawl config to compare against
     * @return Summary and detailed comparison items
     */
    fun compareFolders(
        fsListPath: Path,
        crawlConfigId: Long
    ): Pair<FolderComparisonSummary, List<FolderComparisonItem>>

    /**
     * Compare files from filesystem list against DB, grouped by extension.
     *
     * @param fsListPath Path to file containing file paths (one per line)
     * @param crawlConfigId The crawl config to compare against
     * @return File comparison summary with extension breakdown
     */
    fun compareFiles(
        fsListPath: Path,
        crawlConfigId: Long
    ): FileComparisonSummary

    /**
     * Ad-hoc review of a specific folder - compare FS children against DB.
     * Only reviews immediate children (not recursive).
     *
     * @param folderId The FSFolder ID to review
     * @return Detailed review result with FS vs DB comparison
     */
    fun reviewFolder(folderId: Long): FolderReviewResult

    /**
     * Find the matching pattern for a given path.
     * Returns the first pattern that matches, or null if no pattern matches.
     *
     * @param path The path to check
     * @param patterns List of regex patterns to match against
     * @return The first matching pattern, or null
     */
    fun findMatchingPattern(path: String, patterns: List<String>): String?
}
