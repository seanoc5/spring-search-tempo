package com.oconeco.spring_search_tempo.batch.discovery

import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.RecentCrawlCheckResult
import com.oconeco.spring_search_tempo.base.service.RecentCrawlSkipChecker
import com.oconeco.spring_search_tempo.base.service.StartPathValidator
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.isRegularFile

/**
 * ItemReader for the discovery phase.
 *
 * Performs fast filesystem enumeration with SKIP pattern detection only.
 * Does NOT perform full pattern matching or text extraction - that happens
 * in later phases (Assignment and Progressive Analysis).
 *
 * SKIP Optimization:
 * Folders matching SKIP patterns are not enumerated at all - their children
 * are never listed from the filesystem. This provides significant performance
 * improvement for folders like .git, node_modules, etc.
 *
 * @param startPaths Root directories to discover
 * @param maxDepth Maximum directory depth to traverse
 * @param followLinks Whether to follow symbolic links
 * @param skipPatterns List of SKIP regex patterns (folder only)
 * @param patternMatchingService Service for pattern matching
 * @param recentCrawlChecker Optional checker for skipping recently crawled configs
 * @param maxFilesPerBatch Maximum files per batch (for large directories)
 */
class DiscoveryReader(
    private val startPaths: List<Path>,
    private val maxDepth: Int,
    private val followLinks: Boolean,
    private val skipPatterns: List<String>,
    private val patternMatchingService: PatternMatchingService,
    private val recentCrawlChecker: RecentCrawlSkipChecker? = null,
    private val maxFilesPerBatch: Int = 500
) : ItemReader<DiscoveryFolderItem> {

    companion object {
        private val log = LoggerFactory.getLogger(DiscoveryReader::class.java)
    }

    private var itemIterator: Iterator<DiscoveryFolderItem>? = null
    private val items = ConcurrentLinkedQueue<DiscoveryFolderItem>()
    private var walkCompleted = false

    private val validStartPaths: List<Path>
    val pathWarnings: List<String>

    init {
        log.info(
            "Initializing DiscoveryReader with {} startPaths, maxDepth: {}, followLinks: {}, {} SKIP patterns",
            startPaths.size, maxDepth, followLinks, skipPatterns.size
        )

        val (valid, warnings) = StartPathValidator.validateAndFilter(startPaths)
        validStartPaths = valid
        pathWarnings = warnings

        if (validStartPaths.isEmpty() && startPaths.isNotEmpty()) {
            log.error("No valid start paths! All {} paths are invalid or inaccessible.", startPaths.size)
        }

        initializeWalk()
    }

    /**
     * FileVisitor that discovers folders and collects file paths.
     * Only checks SKIP patterns - full pattern matching happens later.
     */
    private inner class DiscoveryVisitor : SimpleFileVisitor<Path>() {
        private var directoriesProcessed = 0
        private var filesCollected = 0
        private var errorsEncountered = 0
        private var skippedByPattern = 0
        private var skippedByRecentCrawl = 0

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            directoriesProcessed++

            if (directoriesProcessed % 1000 == 0) {
                log.debug(
                    "Discovery progress: {} dirs, {} files, {} SKIP, {} recent-crawl-skip, {} errors",
                    directoriesProcessed, filesCollected, skippedByPattern, skippedByRecentCrawl, errorsEncountered
                )
            }

            try {
                // Check recent crawl first (more expensive, but skips entire subtrees)
                if (recentCrawlChecker != null) {
                    when (val result = recentCrawlChecker.shouldSkipFolder(dir)) {
                        is RecentCrawlCheckResult.SkipSubtree -> {
                            skippedByRecentCrawl++
                            log.info(
                                "Skipping recently crawled subtree: {} (configId={}, crawledAt={})",
                                dir, result.otherCrawlConfigId, result.lastUpdated
                            )
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        is RecentCrawlCheckResult.NotRecentlyCrawled -> {
                            // Continue
                        }
                    }
                }

                // Check SKIP patterns only (fast check)
                val skipResult = patternMatchingService.matchesSkipPatternOnly(dir.toString(), skipPatterns)

                if (skipResult.isSkip) {
                    skippedByPattern++
                    // Add folder with SKIP flag but don't enumerate children
                    items.add(
                        DiscoveryFolderItem(
                            path = dir,
                            skipDetected = true,
                            matchedPattern = skipResult.matchedPattern,
                            filePaths = emptyList(),
                            totalFileCount = 0
                        )
                    )
                    log.debug("SKIP folder discovered (children not enumerated): {}", dir)
                    return FileVisitResult.SKIP_SUBTREE
                }

                // List immediate files (not recursive)
                val immediateFiles = Files.list(dir).use { stream ->
                    stream.filter { it.isRegularFile() }.toList()
                }
                filesCollected += immediateFiles.size

                // Batch large directories
                if (immediateFiles.size <= maxFilesPerBatch) {
                    items.add(
                        DiscoveryFolderItem(
                            path = dir,
                            skipDetected = false,
                            filePaths = immediateFiles,
                            totalFileCount = immediateFiles.size
                        )
                    )
                } else {
                    val batches = immediateFiles.chunked(maxFilesPerBatch)
                    log.info("Splitting large directory ({} files) into {} batches: {}", immediateFiles.size, batches.size, dir)

                    batches.forEachIndexed { index, batch ->
                        items.add(
                            DiscoveryFolderItem(
                                path = dir,
                                skipDetected = false,
                                filePaths = batch,
                                totalFileCount = immediateFiles.size,
                                isContinuation = index > 0
                            )
                        )
                    }
                }

            } catch (e: AccessDeniedException) {
                errorsEncountered++
                log.warn("Access denied to directory (skipping): {} - {}", dir, e.message)
                return FileVisitResult.SKIP_SUBTREE
            } catch (e: NoSuchFileException) {
                errorsEncountered++
                log.warn("Directory disappeared (skipping): {} - {}", dir, e.message)
                return FileVisitResult.SKIP_SUBTREE
            } catch (e: Exception) {
                errorsEncountered++
                log.warn("Error discovering directory (skipping): {} - {}", dir, e.message)
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
            log.info(
                "Discovery walk completed: {} dirs, {} files, {} SKIP pattern, {} recent-crawl-skip, {} errors",
                directoriesProcessed, filesCollected, skippedByPattern, skippedByRecentCrawl, errorsEncountered
            )
        }
    }

    private fun initializeWalk() {
        try {
            val visitOptions = if (followLinks) {
                setOf(FileVisitOption.FOLLOW_LINKS)
            } else {
                emptySet()
            }

            log.info("Starting discovery walk from {} valid paths", validStartPaths.size)

            validStartPaths.forEachIndexed { index, startPath ->
                log.info("Walking start path [{}/{}]: {}", index + 1, validStartPaths.size, startPath)

                val visitor = DiscoveryVisitor()
                try {
                    Files.walkFileTree(startPath, visitOptions, maxDepth, visitor)
                    visitor.logSummary()
                } catch (e: Exception) {
                    log.error("Error walking start path: {} - {}", startPath, e.message)
                }
            }

            itemIterator = items.iterator()
            walkCompleted = true

            log.info("Discovery walk complete. Total items: {}", items.size)

        } catch (e: Exception) {
            log.error("Critical error during discovery initialization", e)
            throw e
        }
    }

    @Synchronized
    override fun read(): DiscoveryFolderItem? {
        return try {
            if (itemIterator?.hasNext() == true) {
                itemIterator!!.next()
            } else {
                if (walkCompleted) {
                    log.info("Finished reading all {} discovery items", items.size)
                }
                null
            }
        } catch (e: Exception) {
            log.error("Error reading next discovery item", e)
            throw e
        }
    }
}
