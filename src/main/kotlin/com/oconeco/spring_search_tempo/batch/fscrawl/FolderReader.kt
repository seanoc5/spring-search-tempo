package com.oconeco.spring_search_tempo.batch.fscrawl

import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
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
    private var folderStream: Stream<Path>? = null

    init {
        log.info("Initializing FolderReader with startPath: {}, maxDepth: {}, followLinks: {}",
            startPath, maxDepth, followLinks)
        initializeStream()
    }

    private fun initializeStream() {
        try {
            val visitOptions = if (followLinks) {
                setOf(FileVisitOption.FOLLOW_LINKS)
            } else {
                emptySet()
            }

            folderStream = Files.walk(startPath, maxDepth, *visitOptions.toTypedArray())
                .filter { it.isDirectory() }
                .onClose { log.info("Folder stream closed") }

            folderIterator = folderStream!!.iterator()
            log.info("Successfully initialized folder stream")
        } catch (e: Exception) {
            log.error("Failed to initialize folder stream from startPath: {}", startPath, e)
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
                // End of stream
                folderStream?.close()
                log.info("Finished reading all folders")
                null
            }
        } catch (e: Exception) {
            log.error("Error reading next folder", e)
            folderStream?.close()
            throw e
        }
    }
}
