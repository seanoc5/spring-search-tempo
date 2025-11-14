package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.isRegularFile

/**
 * ItemReader that performs single-pass directory traversal, collecting both
 * directories and their immediate files together.
 *
 * This replaces the two-phase approach (FolderReader then FileReader) with a
 * more efficient single-pass strategy that processes directories and files together
 * while the directory is "hot" in filesystem cache.
 *
 * Supports multiple start paths for crawling multiple directory trees sequentially
 * with a single shared configuration.
 *
 * SKIP Folder Optimization:
 * Folders matching SKIP patterns are not enumerated at all - their children are never
 * listed from the filesystem. This provides significant performance improvement for
 * folders like .git, node_modules, etc. The SKIP folder itself is still collected
 * (with empty file list) so metadata can be persisted, but enumeration stops there.
 *
 * @param startPaths List of root directories to start crawling from (processed sequentially)
 * @param maxDepth Maximum directory depth to traverse
 * @param followLinks Whether to follow symbolic links
 * @param folderMatcher Optional function to determine if a folder should be skipped (returns AnalysisStatus.SKIP)
 */
class CombinedCrawlReader(
    private val startPaths: List<Path>,
    private val maxDepth: Int,
    private val followLinks: Boolean,
    private val folderMatcher: ((Path) -> AnalysisStatus)? = null
) : ItemReader<CombinedCrawlItem> {

    companion object {
        private val log = LoggerFactory.getLogger(CombinedCrawlReader::class.java)
    }

    private var itemIterator: Iterator<CombinedCrawlItem>? = null
    private val items = ConcurrentLinkedQueue<CombinedCrawlItem>()
    private var walkCompleted = false

    init {
        log.info("Initializing CombinedCrawlReader with {} startPaths, maxDepth: {}, followLinks: {}",
            startPaths.size, maxDepth, followLinks)
        startPaths.forEachIndexed { index, path ->
            log.info("  Start path [{}]: {}", index + 1, path)
        }
        initializeWalk()
    }

    /**
     * Custom FileVisitor that collects directories and their immediate files together.
     * This visitor gracefully handles access errors without terminating the crawl.
     */
    private inner class CombinedFileVisitor : SimpleFileVisitor<Path>() {
        private var directoriesProcessed = 0
        private var filesCollected = 0
        private var errorsEncountered = 0
        private var skippedDirectories = 0

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            directoriesProcessed++

            if (directoriesProcessed % 1000 == 0) {
                log.debug("Progress: {} directories processed, {} files collected, {} skipped, {} errors handled",
                    directoriesProcessed, filesCollected, skippedDirectories, errorsEncountered)
            }

            try {
                // Check if this folder should be skipped (SKIP pattern match)
                val shouldSkip = folderMatcher?.invoke(dir) == AnalysisStatus.SKIP

                if (shouldSkip) {
                    skippedDirectories++
                    // Add directory with empty file list (metadata will be persisted with SKIP status)
                    items.add(CombinedCrawlItem(directory = dir, files = emptyList()))
                    log.debug("Directory matched SKIP pattern (children not enumerated): {}", dir)
                    // Skip enumeration of this folder's children entirely
                    return FileVisitResult.SKIP_SUBTREE
                }

                // List immediate files in this directory (not recursive)
                // TODO: Consider using Files.newDirectoryStream() with filter for potentially better performance
                val immediateFiles = Files.list(dir)
                    .filter { it.isRegularFile() }
                    .toList()

                filesCollected += immediateFiles.size

                // Add directory + its files as a single item
                items.add(CombinedCrawlItem(directory = dir, files = immediateFiles))

                log.trace("Collected directory: {} with {} files", dir, immediateFiles.size)

            } catch (e: AccessDeniedException) {
                errorsEncountered++
                log.warn("Access denied to directory (skipping): {} - {}", dir, e.message)
                return FileVisitResult.SKIP_SUBTREE
            } catch (e: NoSuchFileException) {
                errorsEncountered++
                log.warn("Directory disappeared during processing (skipping): {} - {}", dir, e.message)
                return FileVisitResult.SKIP_SUBTREE
            } catch (e: Exception) {
                errorsEncountered++
                log.warn("Error processing directory (skipping): {} - {}", dir, e.message)
                return FileVisitResult.SKIP_SUBTREE
            }

            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            errorsEncountered++
            log.warn("Unable to access path (skipping): {} - {}", file, exc.message)
            return FileVisitResult.SKIP_SUBTREE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) {
                errorsEncountered++
                log.warn("Error after visiting directory (continuing): {} - {}", dir, exc.message)
            }
            return FileVisitResult.CONTINUE
        }

        fun logSummary() {
            log.info("Directory walk completed: {} directories processed, {} files collected, {} skipped (SKIP pattern), {} errors gracefully handled",
                directoriesProcessed, filesCollected, skippedDirectories, errorsEncountered)
        }
    }

    private fun initializeWalk() {
        try {
            val visitOptions = if (followLinks) {
                setOf(FileVisitOption.FOLLOW_LINKS)
            } else {
                emptySet()
            }

            log.info("Starting combined directory+file walk from {} start paths", startPaths.size)

            // Walk each start path sequentially, adding items to the same queue
            startPaths.forEachIndexed { index, startPath ->
                log.info("Walking start path [{}/{}]: {}", index + 1, startPaths.size, startPath)

                val visitor = CombinedFileVisitor()
                try {
                    Files.walkFileTree(
                        startPath,
                        visitOptions,
                        maxDepth,
                        visitor
                    )
                    visitor.logSummary()
                } catch (e: Exception) {
                    log.error("Error walking start path: {} - {}", startPath, e.message)
                    // Continue with other paths rather than failing entirely
                }
            }

            itemIterator = items.iterator()
            walkCompleted = true

            log.info("Completed walking all {} start paths. Total items collected: {}",
                startPaths.size, items.size)

        } catch (e: Exception) {
            log.error("Critical error during combined walk initialization", e)
            throw e
        }
    }

    override fun read(): CombinedCrawlItem? {
        return try {
            if (itemIterator?.hasNext() == true) {
                val item = itemIterator!!.next()
                log.trace("Read combined item: directory={}, files={}",
                    item.directory, item.files.size)
                item
            } else {
                // End of iteration
                if (walkCompleted) {
                    log.info("Finished reading all {} combined directory+file items", items.size)
                }
                null
            }
        } catch (e: Exception) {
            log.error("Error reading next combined item", e)
            throw e
        }
    }
}
