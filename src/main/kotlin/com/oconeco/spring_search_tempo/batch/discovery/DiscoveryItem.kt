package com.oconeco.spring_search_tempo.batch.discovery

import java.nio.file.Path

/**
 * Item representing a discovered folder during filesystem discovery.
 * Contains only the metadata needed for discovery - no file contents or text extraction.
 */
data class DiscoveryFolderItem(
    /** The folder path */
    val path: Path,
    /** Whether a SKIP pattern matched during enumeration */
    val skipDetected: Boolean = false,
    /** The pattern that matched (for audit trail) */
    val matchedPattern: String? = null,
    /** Files in this folder (not yet processed, just paths) */
    val filePaths: List<Path> = emptyList(),
    /** Total file count (for large directories split into batches) */
    val totalFileCount: Int = 0,
    /** True if this is a continuation batch of a large directory */
    val isContinuation: Boolean = false
)

/**
 * Item representing a discovered file during filesystem discovery.
 * Contains only basic metadata - no text extraction.
 */
data class DiscoveryFileItem(
    /** The file path */
    val path: Path,
    /** Parent folder path */
    val parentPath: Path,
    /** File size in bytes */
    val size: Long,
    /** Last modified timestamp in millis */
    val lastModified: Long,
    /** Whether parent folder was SKIP */
    val parentSkipDetected: Boolean = false
)

/**
 * Result of discovery processing - ready for persistence.
 */
data class DiscoveryResult(
    /** Folder to persist (may be new or updated) */
    val folder: DiscoveredFolder?,
    /** Files to persist (may be new or updated) */
    val files: List<DiscoveredFile>
)

/**
 * Discovered folder ready for persistence.
 */
data class DiscoveredFolder(
    /** URI (path as string) */
    val uri: String,
    /** Display label */
    val label: String,
    /** Whether SKIP pattern matched */
    val skipDetected: Boolean,
    /** Pattern that matched (for analysisStatusReason) */
    val matchedPattern: String?,
    /** Directory depth */
    val crawlDepth: Int,
    /** Directory size in bytes (sum of immediate files) */
    val size: Long,
    /** Last modified time from filesystem */
    val fsLastModified: java.time.OffsetDateTime?,
    /** Unix owner */
    val owner: String?,
    /** Unix group */
    val group: String?,
    /** Unix permissions */
    val permissions: String?
)

/**
 * Discovered file ready for persistence.
 */
data class DiscoveredFile(
    /** URI (path as string) */
    val uri: String,
    /** Display label */
    val label: String,
    /** Parent folder URI */
    val parentUri: String,
    /** File size in bytes */
    val size: Long,
    /** Last modified time from filesystem */
    val fsLastModified: java.time.OffsetDateTime?,
    /** Unix owner */
    val owner: String?,
    /** Unix group */
    val group: String?,
    /** Unix permissions */
    val permissions: String?,
    /** Directory depth */
    val crawlDepth: Int,
    /** Whether parent folder was SKIP */
    val parentSkipDetected: Boolean
)
