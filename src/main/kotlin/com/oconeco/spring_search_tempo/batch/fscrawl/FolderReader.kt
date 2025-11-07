package com.oconeco.spring_search_tempo.batch.fscrawl

import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.isDirectory

class FolderReader(
    private val startPath: Path,
    private val maxDepth: Int,
    private val followLinks: Boolean
) : ItemReader<Path> {

    companion object {
        private val log = LoggerFactory.getLogger(FolderReader::class.java)
    }

    private var folderIterator: Iterator<Path>? = null
    private val folders = ConcurrentLinkedQueue<Path>()
    private var walkCompleted = false

    init {
        log.info("Initializing FolderReader with startPath: {}, maxDepth: {}, followLinks: {}",
            startPath, maxDepth, followLinks)
        initializeWalk()
    }

    /**
     * Custom FileVisitor that gracefully handles access errors without terminating the crawl.
     * This prevents the batch job from failing when encountering broken symbolic links,
     * permission denied errors, or other file system issues (e.g., Windows junction points
     * like "My Music" that may not exist).
     */
    private inner class ErrorHandlingFileVisitor : SimpleFileVisitor<Path>() {
        private var foldersFound = 0
        private var errorsEncountered = 0

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            folders.add(dir)
            foldersFound++

            if (foldersFound % 1000 == 0) {
                log.debug("Progress: {} folders discovered, {} errors handled", foldersFound, errorsEncountered)
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
            log.info("Folder walk completed: {} folders found, {} errors gracefully handled",
                foldersFound, errorsEncountered)
        }
    }

    private fun initializeWalk() {
        try {
            val visitOptions = if (followLinks) {
                setOf(FileVisitOption.FOLLOW_LINKS)
            } else {
                emptySet()
            }

            log.info("Starting directory walk from: {}", startPath)

            val visitor = ErrorHandlingFileVisitor()
            Files.walkFileTree(
                startPath,
                visitOptions,
                maxDepth,
                visitor
            )

            visitor.logSummary()
            folderIterator = folders.iterator()
            walkCompleted = true

        } catch (e: Exception) {
            log.error("Critical error during folder walk initialization from startPath: {}", startPath, e)
            throw e
        }
    }

    override fun read(): Path? {
        return try {
            if (folderIterator?.hasNext() == true) {
                val folder = folderIterator!!.next()
                log.debug("Read folder: {}", folder)
                folder
            } else {
                // End of iteration
                if (walkCompleted) {
                    log.info("Finished reading all {} accessible folders", folders.size)
                }
                null
            }
        } catch (e: Exception) {
            log.error("Error reading next folder", e)
            throw e
        }
    }
}
