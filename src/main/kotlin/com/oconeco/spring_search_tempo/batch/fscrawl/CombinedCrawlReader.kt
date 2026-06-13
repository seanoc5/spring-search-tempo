package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.service.RecentCrawlCheckResult
import com.oconeco.spring_search_tempo.base.service.RecentCrawlSkipChecker
import com.oconeco.spring_search_tempo.base.service.StartPathValidator
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
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
 * Recent Crawl Skip:
 * When recentCrawlChecker is provided, folders that are crawl config roots (startPaths
 * of other configs) and were recently crawled by another config will be skipped entirely.
 * This prevents duplicate work when parent crawls encounter child crawl territories.
 *
 * Deterministic walk order:
 * Subdirectories and immediate files in each directory are sorted by URI so that the
 * walk order is reproducible across JVM restarts. This is required for resume-from-
 * checkpoint to work correctly — without sorting, a crashed crawl could re-process
 * different items on restart because [Files.newDirectoryStream] does not guarantee
 * order.
 *
 * Checkpoint resume:
 * When [StepExecution] contains [RESUME_FROM_URI_KEY], the reader skips all items
 * whose directory URI sorts at or before the resume URI. The crawl picks up at the
 * directory immediately *after* the last successfully persisted one.
 *
 * @param startPaths List of root directories to start crawling from (processed sequentially)
 * @param maxDepth Maximum directory depth to traverse
 * @param followLinks Whether to follow symbolic links
 * @param folderMatcher Optional function to determine if a folder should be skipped (returns AnalysisStatus.SKIP)
 * @param recentCrawlChecker Optional checker for skipping folders recently crawled by other configs
 */
class CombinedCrawlReader(
    private val startPaths: List<Path>,
    private val maxDepth: Int,
    private val followLinks: Boolean,
    private val folderMatcher: ((Path) -> AnalysisStatus)? = null,
    private val recentCrawlChecker: RecentCrawlSkipChecker? = null,
    private val maxFilesPerBatch: Int = 500
) : ItemReader<CombinedCrawlItem>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(CombinedCrawlReader::class.java)

        /** Key under which the resume URI is stored in the step/job execution context. */
        const val RESUME_FROM_URI_KEY = "crawlResumeFromUri"
    }

    private var itemIterator: Iterator<CombinedCrawlItem>? = null
    private val items = ConcurrentLinkedQueue<CombinedCrawlItem>()
    private var walkCompleted = false
    private var resumeFromUri: String? = null

    /** Valid paths after filtering invalid ones */
    private val validStartPaths: List<Path>

    /** Warnings for invalid paths (accessible by job tracking) */
    val pathWarnings: List<String>

    init {
        log.info("Initializing CombinedCrawlReader with {} startPaths, maxDepth: {}, followLinks: {}",
            startPaths.size, maxDepth, followLinks)
        startPaths.forEachIndexed { index, path ->
            log.info("  Start path [{}]: {}", index + 1, path)
        }

        // Validate paths and filter out invalid ones
        val (valid, warnings) = StartPathValidator.validateAndFilter(startPaths)
        validStartPaths = valid
        pathWarnings = warnings

        if (validStartPaths.isEmpty() && startPaths.isNotEmpty()) {
            log.error("No valid start paths! All {} paths are invalid or inaccessible.", startPaths.size)
        } else if (validStartPaths.size < startPaths.size) {
            log.warn("Using {} of {} start paths ({} invalid/inaccessible)",
                validStartPaths.size, startPaths.size, startPaths.size - validStartPaths.size)
        }
    }

    override fun beforeStep(stepExecution: StepExecution) {
        // Pull resume marker from the job execution context (set by CrawlCheckpointListener)
        // or from the step context (so callers can override directly in tests).
        val jobCtx = stepExecution.jobExecution.executionContext
        val stepCtx = stepExecution.executionContext
        resumeFromUri = when {
            stepCtx.containsKey(RESUME_FROM_URI_KEY) -> stepCtx.getString(RESUME_FROM_URI_KEY)
            jobCtx.containsKey(RESUME_FROM_URI_KEY) -> jobCtx.getString(RESUME_FROM_URI_KEY)
            else -> null
        }
        if (resumeFromUri != null) {
            log.info("Resuming crawl from checkpoint URI: {}", resumeFromUri)
        }
        initializeWalk()
    }

    /**
     * Walk a single startPath using sorted directory listings so the order is
     * reproducible across JVM restarts. Replaces [Files.walkFileTree] (whose
     * order is implementation-defined) with an explicit depth-first traversal.
     */
    private fun walkSorted(startPath: Path) {
        var directoriesProcessed = 0
        var filesCollected = 0
        var errorsEncountered = 0
        var skippedDirectories = 0
        var skippedByRecentCrawl = 0
        var skippedByCheckpoint = 0

        // Depth-first stack of (directory, depthFromStart).
        val stack = ArrayDeque<Pair<Path, Int>>()
        stack.addLast(startPath to 0)

        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            if (depth > maxDepth) {
                continue
            }
            directoriesProcessed++

            if (directoriesProcessed % 1000 == 0) {
                log.debug(
                    "Progress: {} directories processed, {} files collected, {} skipped (pattern), {} skipped (recent crawl), {} skipped (checkpoint), {} errors handled",
                    directoriesProcessed, filesCollected, skippedDirectories,
                    skippedByRecentCrawl, skippedByCheckpoint, errorsEncountered
                )
            }

            // Follow-links / cycle protection: skip directories that aren't directories anymore.
            // BasicFileAttributes for symlink loop detection mirrors walkFileTree's default behavior.
            val attrs: BasicFileAttributes? = try {
                if (followLinks) Files.readAttributes(dir, BasicFileAttributes::class.java)
                else Files.readAttributes(
                    dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                )
            } catch (e: IOException) {
                errorsEncountered++
                log.info("Cannot stat directory (skipping): {} - {}", dir, e.message)
                null
            }
            if (attrs == null || !attrs.isDirectory) {
                continue
            }

            try {
                // Recent-crawl skip check (only fires at crawl-config roots).
                if (recentCrawlChecker != null) {
                    when (val result = recentCrawlChecker.shouldSkipFolder(dir)) {
                        is RecentCrawlCheckResult.SkipSubtree -> {
                            skippedByRecentCrawl++
                            log.info(
                                "Skipping subtree recently crawled by another config: {} (configId={}, status={}, crawledAt={})",
                                dir, result.otherCrawlConfigId, result.otherAnalysisStatus, result.lastUpdated
                            )
                            continue
                        }
                        is RecentCrawlCheckResult.OwnedByOtherCrawl -> {
                            skippedByRecentCrawl++
                            log.info(
                                "Skipping subtree owned by another crawl (structural): {} (owner={})",
                                dir, result.otherCrawlName
                            )
                            continue
                        }
                        is RecentCrawlCheckResult.NotRecentlyCrawled -> Unit
                    }
                }

                // SKIP-pattern check: collect folder metadata but do not enumerate children.
                val shouldSkip = folderMatcher?.invoke(dir) == AnalysisStatus.SKIP
                if (shouldSkip) {
                    skippedDirectories++
                    if (shouldEmit(dir)) {
                        items.add(CombinedCrawlItem(directory = dir, files = emptyList()))
                    } else {
                        skippedByCheckpoint++
                    }
                    log.debug("Directory matched SKIP pattern (children not enumerated): {}", dir)
                    continue
                }

                // List immediate children in deterministic order.
                val children = Files.list(dir).use { stream ->
                    stream.toList()
                }.sortedBy { it.toString() }

                val immediateFiles = children.filter { it.isRegularFile() }
                val immediateDirs = children.filter {
                    try {
                        val ca = if (followLinks) {
                            Files.readAttributes(it, BasicFileAttributes::class.java)
                        } else {
                            Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        }
                        ca.isDirectory
                    } catch (e: IOException) {
                        false
                    }
                }

                filesCollected += immediateFiles.size

                // Emit the directory + files (possibly split into continuation batches).
                if (shouldEmit(dir)) {
                    if (immediateFiles.size <= maxFilesPerBatch) {
                        items.add(
                            CombinedCrawlItem(
                                directory = dir,
                                files = immediateFiles,
                                isContinuation = false,
                                totalFileCount = immediateFiles.size
                            )
                        )
                        log.trace("Collected directory: {} with {} files", dir, immediateFiles.size)
                    } else {
                        val batches = immediateFiles.chunked(maxFilesPerBatch)
                        log.info(
                            "Splitting large directory ({} files) into {} batches: {}",
                            immediateFiles.size, batches.size, dir
                        )
                        batches.forEachIndexed { index, batch ->
                            items.add(
                                CombinedCrawlItem(
                                    directory = dir,
                                    files = batch,
                                    isContinuation = index > 0,
                                    totalFileCount = immediateFiles.size
                                )
                            )
                        }
                    }
                } else {
                    skippedByCheckpoint++
                    log.trace("Resuming past directory (already processed): {}", dir)
                }

                // Push subdirectories in reverse-sorted order so popping yields sorted order.
                for (childDir in immediateDirs.asReversed()) {
                    stack.addLast(childDir to (depth + 1))
                }

            } catch (e: AccessDeniedException) {
                errorsEncountered++
                log.info("Access denied to directory (skipping): {}", dir)
            } catch (e: NoSuchFileException) {
                errorsEncountered++
                log.info("Directory disappeared during processing (skipping): {}", dir)
            } catch (e: IOException) {
                errorsEncountered++
                log.warn("IO error processing directory (skipping): {} - {}", dir, e.message)
            } catch (e: RuntimeException) {
                errorsEncountered++
                log.warn("Error processing directory (skipping): {} - {}", dir, e.message)
            }
        }

        log.info(
            "Directory walk completed for {}: {} directories processed, {} files collected, {} skipped (SKIP pattern), {} skipped (recent crawl), {} skipped (checkpoint), {} errors gracefully handled",
            startPath, directoriesProcessed, filesCollected, skippedDirectories,
            skippedByRecentCrawl, skippedByCheckpoint, errorsEncountered
        )
    }

    /**
     * Honor the checkpoint by suppressing items whose directory URI sorts at or before
     * the resume marker. Walks are deterministic (sorted), so this corresponds to
     * directories the previous run already persisted.
     */
    private fun shouldEmit(dir: Path): Boolean {
        val marker = resumeFromUri ?: return true
        return dir.toString() > marker
    }

    private fun initializeWalk() {
        if (walkCompleted) {
            log.debug("Walk already completed; skipping re-initialization")
            return
        }
        try {
            log.info(
                "Starting combined directory+file walk from {} valid start paths (of {} total); resumeFromUri={}",
                validStartPaths.size, startPaths.size, resumeFromUri
            )

            validStartPaths.forEachIndexed { index, startPath ->
                log.info("Walking start path [{}/{}]: {}", index + 1, validStartPaths.size, startPath)
                try {
                    walkSorted(startPath)
                } catch (e: Exception) {
                    log.error("Error walking start path: {} - {}", startPath, e.message)
                    // Continue with other paths rather than failing entirely
                }
            }

            itemIterator = items.iterator()
            walkCompleted = true

            log.info(
                "Completed walking all {} valid start paths. Total items collected: {}",
                validStartPaths.size, items.size
            )

        } catch (e: Exception) {
            log.error("Critical error during combined walk initialization", e)
            throw e
        }
    }

    @Synchronized
    override fun read(): CombinedCrawlItem? {
        if (itemIterator == null) {
            // Reader may be used outside a Spring Batch step (e.g. unit tests).
            initializeWalk()
        }
        return try {
            if (itemIterator?.hasNext() == true) {
                val item = itemIterator!!.next()
                log.trace("Read combined item: directory={}, files={}",
                    item.directory, item.files.size)
                item
            } else {
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
