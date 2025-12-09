package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter

/**
 * Writer that persists both folders and files from combined crawl results.
 * Uses batch operations for efficiency when multiple items are written together.
 *
 * Persists all items including those with SKIP status (metadata only).
 * SKIP items are stored in the database for audit trail and UI filtering.
 *
 * Sets jobRunId on all persisted entities for tracking purposes.
 *
 * @param folderService Service for persisting folders
 * @param fileService Service for persisting files
 */
class CombinedCrawlWriter(
    private val folderService: FSFolderService,
    private val fileService: FSFileService
) : ItemWriter<CombinedCrawlResult>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(CombinedCrawlWriter::class.java)
    }

    private var currentJobRunId: Long? = null
    private lateinit var currentStepExecution: StepExecution

    override fun beforeStep(stepExecution: StepExecution) {
        currentStepExecution = stepExecution
        currentJobRunId = stepExecution.executionContext.getLong(CrawlStepListener.JOB_RUN_ID_KEY, -1L)
            .takeIf { it > 0 }
        log.info("Writer initialized with jobRunId: {}", currentJobRunId)
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
                    // Set jobRunId if available
                    folder.jobRunId = currentJobRunId

                    val isNew = folder.id == null
                    if (isNew) {
                        folderService.create(folder)
                    } else {
                        folderService.update(folder.id!!, folder)
                    }
                    foldersWritten++

                    // Track statistics
                    incrementFolderCounter(isNew, folder.analysisStatus)
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
                    // Set jobRunId if available
                    file.jobRunId = currentJobRunId

                    val isNew = file.id == null
                    if (isNew) {
                        fileService.create(file)
                    } else {
                        fileService.update(file.id!!, file)
                    }
                    filesWritten++

                    // Track statistics
                    incrementFileCounter(isNew, file.analysisStatus)
                } catch (e: Exception) {
                    log.error("Failed to save file: {}", file.uri, e)
                    incrementFileError()
                    // Continue with other items rather than failing entire batch
                }
            }
        }

        log.info("Wrote chunk: {} folders, {} files", foldersWritten, filesWritten)
    }

    private fun incrementFolderCounter(isNew: Boolean, analysisStatus: com.oconeco.spring_search_tempo.base.domain.AnalysisStatus?) {
        if (!::currentStepExecution.isInitialized) return

        val context = currentStepExecution.executionContext
        context.putLong("foldersDiscovered", context.getLong("foldersDiscovered", 0) + 1)

        if (analysisStatus == com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.SKIP) {
            context.putLong("foldersSkipped", context.getLong("foldersSkipped", 0) + 1)
        } else if (isNew) {
            context.putLong("foldersNew", context.getLong("foldersNew", 0) + 1)
        } else {
            context.putLong("foldersUpdated", context.getLong("foldersUpdated", 0) + 1)
        }
    }

    private fun incrementFileCounter(isNew: Boolean, analysisStatus: com.oconeco.spring_search_tempo.base.domain.AnalysisStatus?) {
        if (!::currentStepExecution.isInitialized) return

        val context = currentStepExecution.executionContext
        context.putLong("filesDiscovered", context.getLong("filesDiscovered", 0) + 1)

        if (analysisStatus == com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.SKIP) {
            context.putLong("filesSkipped", context.getLong("filesSkipped", 0) + 1)
        } else if (isNew) {
            context.putLong("filesNew", context.getLong("filesNew", 0) + 1)
        } else {
            context.putLong("filesUpdated", context.getLong("filesUpdated", 0) + 1)
        }
    }

    private fun incrementFileError() {
        if (!::currentStepExecution.isInitialized) return

        val context = currentStepExecution.executionContext
        context.putLong("filesError", context.getLong("filesError", 0) + 1)
    }
}
