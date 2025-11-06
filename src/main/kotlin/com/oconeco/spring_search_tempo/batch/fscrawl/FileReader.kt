package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * ItemReader that reads files from previously crawled folders.
 *
 * Strategy:
 * 1. Query FSFolder records from database (already indexed by FolderReader)
 * 2. For each folder, list its immediate files (not recursive - folders already crawled)
 * 3. Return files one at a time for processing
 *
 * @param folderRepository Repository to fetch indexed folders
 * @param batchSize Number of folders to fetch at a time
 */
class FileReader(
    private val folderRepository: FSFolderRepository,
    private val batchSize: Int = 100
) : ItemReader<Path> {

    companion object {
        private val log = LoggerFactory.getLogger(FileReader::class.java)
    }

    private var currentFolderBatch: List<String> = emptyList()
    private var currentBatchIndex = 0
    private var currentFolderIndex = 0
    private var currentFileIterator: Iterator<Path>? = null
    private var totalFoldersProcessed = 0
    private var totalFilesRead = 0

    init {
        log.info("Initializing FileReader with batchSize: {}", batchSize)
        loadNextFolderBatch()
    }

    /**
     * Load the next batch of folders from the database.
     */
    private fun loadNextFolderBatch() {
        try {
            // Query folders in batches using pagination
            val folders = folderRepository.findAll()

            if (folders.isEmpty()) {
                log.warn("No folders found in database. Run folder crawl first.")
                currentFolderBatch = emptyList()
                return
            }

            // Extract URIs (file paths) from folders, filtering nulls
            currentFolderBatch = folders.mapNotNull { it.uri }
            currentBatchIndex++
            currentFolderIndex = 0

            log.info("Loaded batch #{} with {} folders", currentBatchIndex, currentFolderBatch.size)
        } catch (e: Exception) {
            log.error("Failed to load folder batch", e)
            throw e
        }
    }

    /**
     * Load files from the next folder in the current batch.
     */
    private fun loadNextFolder(): Boolean {
        while (currentFolderIndex < currentFolderBatch.size) {
            val folderUri = currentFolderBatch[currentFolderIndex]
            currentFolderIndex++
            totalFoldersProcessed++

            try {
                val folderPath = Path(folderUri)

                if (!Files.exists(folderPath)) {
                    log.warn("Folder no longer exists: {}", folderUri)
                    continue
                }

                if (!Files.isDirectory(folderPath)) {
                    log.warn("Path is not a directory: {}", folderUri)
                    continue
                }

                // List immediate files in this folder (not recursive)
                val files = Files.list(folderPath)
                    .asSequence()
                    .filter { it.isRegularFile() }
                    .iterator()

                if (files.hasNext()) {
                    currentFileIterator = files
                    log.debug("Found files in folder: {}", folderUri)
                    return true
                } else {
                    log.debug("No files in folder: {}", folderUri)
                }
            } catch (e: Exception) {
                log.error("Error accessing folder: {}", folderUri, e)
                // Continue to next folder instead of failing
            }
        }

        return false
    }

    override fun read(): Path? {
        try {
            // Try to read from current file iterator
            while (true) {
                if (currentFileIterator?.hasNext() == true) {
                    val file = currentFileIterator!!.next()
                    totalFilesRead++

                    if (totalFilesRead % 1000 == 0) {
                        log.info("Progress: {} files read from {} folders",
                            totalFilesRead, totalFoldersProcessed)
                    }

                    return file
                }

                // Current folder exhausted, load next folder
                if (!loadNextFolder()) {
                    // All folders exhausted
                    log.info("Finished reading all files. Total: {} files from {} folders",
                        totalFilesRead, totalFoldersProcessed)
                    return null
                }
            }
        } catch (e: Exception) {
            log.error("Error reading next file", e)
            throw e
        }
    }
}
