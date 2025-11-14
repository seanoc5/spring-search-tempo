package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter

/**
 * Writer that persists both folders and files from combined crawl results.
 * Uses batch operations for efficiency when multiple items are written together.
 *
 * Persists all items including those with SKIP status (metadata only).
 * SKIP items are stored in the database for audit trail and UI filtering.
 *
 * @param folderService Service for persisting folders
 * @param fileService Service for persisting files
 */
class CombinedCrawlWriter(
    private val folderService: FSFolderService,
    private val fileService: FSFileService
) : ItemWriter<CombinedCrawlResult> {

    companion object {
        private val log = LoggerFactory.getLogger(CombinedCrawlWriter::class.java)
    }

    override fun write(chunk: Chunk<out CombinedCrawlResult>) {
        if (chunk.isEmpty()) {
            return
        }

        log.debug("Writing chunk of {} combined items", chunk.size())

        // Separate folders and files for batch persistence
        val folders = chunk.mapNotNull { it.folder }
        val allFiles = chunk.flatMap { it.files }

        var foldersWritten = 0
        var filesWritten = 0

        // Persist folders first (files may reference them as parents)
        if (folders.isNotEmpty()) {
            log.trace("Persisting {} folders", folders.size)
            folders.forEach { folder ->
                try {
                    if (folder.id == null) {
                        folderService.create(folder)
                    } else {
                        folderService.update(folder.id!!, folder)
                    }
                    foldersWritten++
                } catch (e: Exception) {
                    log.error("Failed to save folder: {}", folder.uri, e)
                    // Continue with other items rather than failing entire batch
                }
            }
        }

        // Persist files
        if (allFiles.isNotEmpty()) {
            log.trace("Persisting {} files", allFiles.size)
            allFiles.forEach { file ->
                try {
                    if (file.id == null) {
                        fileService.create(file)
                    } else {
                        fileService.update(file.id!!, file)
                    }
                    filesWritten++
                } catch (e: Exception) {
                    log.error("Failed to save file: {}", file.uri, e)
                    // Continue with other items rather than failing entire batch
                }
            }
        }

        log.info("Wrote chunk: {} folders, {} files", foldersWritten, filesWritten)
    }
}
