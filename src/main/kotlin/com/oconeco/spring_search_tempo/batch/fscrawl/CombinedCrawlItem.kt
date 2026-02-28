package com.oconeco.spring_search_tempo.batch.fscrawl

import java.nio.file.Path

/**
 * Represents a directory and its immediate files for combined processing.
 * This enables single-pass crawling where folders and files are processed together.
 *
 * For large directories (500+ files), the files are split into multiple items:
 * - First item: isContinuation=false, folder is processed and persisted
 * - Subsequent items: isContinuation=true, folder processing is skipped
 *
 * @param directory The directory path
 * @param files List of regular files in this batch (subset if large directory)
 * @param isContinuation True if this is a continuation batch (folder already processed)
 * @param totalFileCount Total files in directory (for accurate folder.size on first batch)
 */
data class CombinedCrawlItem(
    val directory: Path,
    val files: List<Path>,
    val isContinuation: Boolean = false,
    val totalFileCount: Int = files.size
)
