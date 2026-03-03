package com.oconeco.remotecrawler.util

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * Lightweight filesystem metadata for efficient processing.
 *
 * @param name File or folder name
 * @param path Full filesystem path
 * @param size File size in bytes (0 for directories)
 * @param lastModified Last modification timestamp
 */
data class FileSystemMetadata(
    val name: String,
    val path: Path,
    val size: Long,
    val lastModified: OffsetDateTime?
) {
    companion object {
        private val log = LoggerFactory.getLogger(FileSystemMetadata::class.java)

        /**
         * Extract lightweight metadata from a filesystem path.
         *
         * @param path Filesystem path to extract metadata from
         * @return FileSystemMetadata or null if path is inaccessible
         */
        fun fromPath(path: Path): FileSystemMetadata? {
            return try {
                val name = path.name

                val size = try {
                    if (Files.isRegularFile(path)) {
                        path.fileSize()
                    } else {
                        0L
                    }
                } catch (e: Exception) {
                    log.warn("Failed to get size for: {}", path, e)
                    0L
                }

                val lastModified = try {
                    val fileTime = Files.getLastModifiedTime(path)
                    OffsetDateTime.ofInstant(fileTime.toInstant(), ZoneId.systemDefault())
                        .truncatedTo(ChronoUnit.MILLIS)
                } catch (e: Exception) {
                    log.warn("Failed to get last modified time for: {}", path, e)
                    null
                }

                FileSystemMetadata(
                    name = name,
                    path = path,
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
     * Format last modified as ISO string for JSON serialization.
     */
    fun lastModifiedIso(): String? = lastModified?.toString()
}
