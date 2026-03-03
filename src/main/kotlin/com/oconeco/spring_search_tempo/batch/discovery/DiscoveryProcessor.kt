package com.oconeco.spring_search_tempo.batch.discovery

import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Processor for the discovery phase.
 *
 * Extracts filesystem metadata from discovered folders and files.
 * Does NOT perform:
 * - Full pattern matching (happens in Assignment phase)
 * - Text extraction (happens in Analysis phase)
 *
 * This processor focuses on fast metadata collection:
 * - File/folder attributes (size, timestamps, permissions)
 * - Directory depth calculation
 * - SKIP flag propagation
 */
class DiscoveryProcessor(
    private val startPaths: List<Path>
) : ItemProcessor<DiscoveryFolderItem, DiscoveryResult> {

    companion object {
        private val log = LoggerFactory.getLogger(DiscoveryProcessor::class.java)
    }

    override fun process(item: DiscoveryFolderItem): DiscoveryResult? {
        try {
            val folderPath = item.path

            // Skip continuation batches for folder processing (folder already processed)
            val folder = if (!item.isContinuation) {
                processFolder(item)
            } else {
                null
            }

            // Process files in this batch
            val files = item.filePaths.mapNotNull { filePath ->
                processFile(filePath, folderPath, item.skipDetected)
            }

            return DiscoveryResult(folder = folder, files = files)

        } catch (e: Exception) {
            log.error("Error processing discovery item for {}: {}", item.path, e.message)
            return null
        }
    }

    /**
     * Process folder metadata.
     */
    private fun processFolder(item: DiscoveryFolderItem): DiscoveredFolder {
        val path = item.path
        val attrs = try {
            Files.readAttributes(path, "posix:*")
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX filesystem (Windows)
            Files.readAttributes(path, "*")
        } catch (e: Exception) {
            log.debug("Could not read attributes for {}: {}", path, e.message)
            emptyMap<String, Any>()
        }

        val posixView = try {
            Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        } catch (e: Exception) {
            null
        }

        val owner = try {
            posixView?.readAttributes()?.owner()?.name
        } catch (e: Exception) {
            null
        }

        val group = try {
            posixView?.readAttributes()?.group()?.name
        } catch (e: Exception) {
            null
        }

        val permissions = try {
            posixView?.readAttributes()?.permissions()?.let {
                PosixFilePermissions.toString(it)
            }
        } catch (e: Exception) {
            null
        }

        val lastModified = try {
            val instant = (attrs["lastModifiedTime"] as? java.nio.file.attribute.FileTime)?.toInstant()
            instant?.atOffset(ZoneOffset.UTC)
        } catch (e: Exception) {
            null
        }

        // Calculate folder size as sum of immediate file sizes
        val size = item.filePaths.sumOf { filePath ->
            try {
                Files.size(filePath)
            } catch (e: Exception) {
                0L
            }
        }

        return DiscoveredFolder(
            uri = path.toString(),
            label = path.fileName?.toString() ?: path.toString(),
            skipDetected = item.skipDetected,
            matchedPattern = item.matchedPattern,
            crawlDepth = calculateDepth(path),
            size = size,
            fsLastModified = lastModified,
            owner = owner,
            group = group,
            permissions = permissions
        )
    }

    /**
     * Process file metadata.
     */
    private fun processFile(path: Path, parentPath: Path, parentSkipDetected: Boolean): DiscoveredFile? {
        try {
            val attrs = try {
                Files.readAttributes(path, "posix:*")
            } catch (e: UnsupportedOperationException) {
                Files.readAttributes(path, "*")
            } catch (e: Exception) {
                log.debug("Could not read attributes for {}: {}", path, e.message)
                emptyMap<String, Any>()
            }

            val posixView = try {
                Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            } catch (e: Exception) {
                null
            }

            val owner = try {
                posixView?.readAttributes()?.owner()?.name
            } catch (e: Exception) {
                null
            }

            val group = try {
                posixView?.readAttributes()?.group()?.name
            } catch (e: Exception) {
                null
            }

            val permissions = try {
                posixView?.readAttributes()?.permissions()?.let {
                    PosixFilePermissions.toString(it)
                }
            } catch (e: Exception) {
                null
            }

            val lastModified = try {
                val instant = (attrs["lastModifiedTime"] as? java.nio.file.attribute.FileTime)?.toInstant()
                instant?.atOffset(ZoneOffset.UTC)
            } catch (e: Exception) {
                null
            }

            val size = try {
                Files.size(path)
            } catch (e: Exception) {
                0L
            }

            return DiscoveredFile(
                uri = path.toString(),
                label = path.fileName?.toString() ?: path.toString(),
                parentUri = parentPath.toString(),
                size = size,
                fsLastModified = lastModified,
                owner = owner,
                group = group,
                permissions = permissions,
                crawlDepth = calculateDepth(path),
                parentSkipDetected = parentSkipDetected
            )

        } catch (e: Exception) {
            log.warn("Error processing file {}: {}", path, e.message)
            return null
        }
    }

    /**
     * Calculate depth relative to start paths.
     */
    private fun calculateDepth(path: Path): Int {
        val absolutePath = path.toAbsolutePath().normalize()

        for (startPath in startPaths) {
            val normalizedStart = startPath.toAbsolutePath().normalize()
            if (absolutePath.startsWith(normalizedStart)) {
                return absolutePath.nameCount - normalizedStart.nameCount
            }
        }

        // Fallback: use absolute depth
        return absolutePath.nameCount
    }
}
