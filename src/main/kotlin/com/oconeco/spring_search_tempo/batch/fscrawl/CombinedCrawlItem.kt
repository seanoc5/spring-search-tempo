package com.oconeco.spring_search_tempo.batch.fscrawl

import java.nio.file.Path

/**
 * Represents a directory and its immediate files for combined processing.
 * This enables single-pass crawling where folders and files are processed together.
 *
 * @param directory The directory path
 * @param files List of regular files in this directory (not recursive)
 */
data class CombinedCrawlItem(
    val directory: Path,
    val files: List<Path>
)
