package com.oconeco.spring_search_tempo.batch.fscrawl

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * Lightweight filesystem metadata for efficient staleness comparison.
 * Used to determine if a file/folder has changed since last crawl without
 * expensive operations like text extraction.
 *
 * @param name File or folder name
 * @param size File size in bytes (0 for directories)
 * @param lastModified Last modification timestamp
 */
data class FileSystemMetadata(
    val name: String,
    val size: Long,
    val lastModified: OffsetDateTime?
) {
    companion object {
        private val log = LoggerFactory.getLogger(FileSystemMetadata::class.java)

        /**
         * Extract lightweight metadata from a filesystem path.
         * Simple approach for now - gathers name, size, and timestamp.
         *
         * TODO: Consider more efficient bulk metadata gathering:
         * - Java NIO DirectoryStream with custom filter
         * - Parallel metadata collection for large directories
         * - Operating system-specific optimizations (e.g., bulk stat calls)
         * - Caching filesystem attributes during tree walk
         *
         * @param path Filesystem path to extract metadata from
         * @return FileSystemMetadata or null if path is inaccessible
         */
        fun fromPath(path: Path): FileSystemMetadata? {
            return try {
                val name = path.name

                // Get file size (0 for directories)
                val size = try {
                    if (Files.isRegularFile(path)) {
                        path.fileSize()
                    } else {
                        log.info("Path is not a regular file: {}", path)
                        0L // Directories don't have meaningful size
                    }
                } catch (e: Exception) {
                    log.warn("Failed to get size for: {}, using 0", path, e)
                    0L
                }

                // Get last modified time
                val lastModified = try {
                    val fileTime = Files.getLastModifiedTime(path)
                    OffsetDateTime.ofInstant(
                        fileTime.toInstant(),
                        ZoneId.systemDefault()
                    )
                } catch (e: Exception) {
                    log.warn("Failed to get last modified time for: {}", path, e)
                    null
                }

                FileSystemMetadata(
                    name = name,
                    size = size,
                    lastModified = lastModified
                )
            } catch (e: Exception) {
                log.warn("Failed to extract metadata from path: {}", path, e)
                null
            }
        }
    }

    /**
     * Check if this metadata indicates the file/folder is unchanged compared to DB record.
     *
     * Comparison strategy:
     * 1. Name must match (if renamed, treat as different entity)
     * 2. Size must match for files (quick check before expensive timestamp comparison)
     * 3. Last modified timestamp must match exactly
     *
     * @param dbLastModified Last modified timestamp from database
     * @param dbSize Size from database (for files)
     * @return true if metadata indicates no changes
     */
    fun isUnchanged(dbLastModified: OffsetDateTime?, dbSize: Long?): Boolean {
        // If we don't have filesystem lastModified, can't determine staleness
        if (lastModified == null) {
            return false
        }

        // If DB doesn't have lastModified, treat as changed (needs update)
        if (dbLastModified == null) {
            return false
        }

        // For files, size mismatch is quick indicator of change
        if (size > 0 && dbSize != null && size != dbSize) {
            log.trace("Size mismatch: fs={}, db={}", size, dbSize)
            return false
        }

        // Timestamp comparison (primary staleness check)
        val timestampMatches = lastModified.isEqual(dbLastModified)

        if (!timestampMatches) {
            log.trace("Timestamp mismatch: fs={}, db={}", lastModified, dbLastModified)
        }

        return timestampMatches
    }
}
