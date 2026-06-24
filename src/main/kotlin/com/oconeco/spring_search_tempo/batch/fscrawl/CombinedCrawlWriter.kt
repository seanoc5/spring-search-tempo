package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.CrawlCheckpointService
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * Writer that persists both folders and files from combined crawl results.
 * Uses bulk saveAll() for efficiency, with per-item fallback on failure.
 *
 * Persists all items including those with SKIP status (metadata only).
 * SKIP items are stored in the database for audit trail and UI filtering.
 *
 * Sets jobRunId on all persisted entities for tracking purposes.
 *
 * @param folderService Service for per-item fallback persistence
 * @param fileService Service for per-item fallback persistence
 * @param folderRepository Repository for bulk folder operations
 * @param fileRepository Repository for bulk file operations
 * @param folderMapper Mapper for DTO-to-entity conversion
 * @param fileMapper Mapper for DTO-to-entity conversion
 */
class CombinedCrawlWriter(
    private val folderService: FSFolderService,
    private val fileService: FSFileService,
    private val folderRepository: FSFolderRepository,
    private val fileRepository: FSFileRepository,
    private val folderMapper: FSFolderMapper,
    private val fileMapper: FSFileMapper,
    private val checkpointService: CrawlCheckpointService? = null,
    private val contentChunkRepository: ContentChunkRepository? = null
) : ItemWriter<CombinedCrawlResult>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(CombinedCrawlWriter::class.java)
    }

    private var currentJobRunId: Long? = null
    private var currentCrawlConfigId: Long? = null
    private lateinit var currentStepExecution: StepExecution

    // Thread-safe counters for step statistics (accessed from multiple chunk-processing threads)
    private val foldersDiscovered = AtomicLong(0)
    private val foldersNew = AtomicLong(0)
    private val foldersUpdated = AtomicLong(0)
    private val foldersSkipped = AtomicLong(0)
    private val filesDiscovered = AtomicLong(0)
    private val filesNew = AtomicLong(0)
    private val filesUpdated = AtomicLong(0)
    private val filesSkipped = AtomicLong(0)
    private val filesError = AtomicLong(0)
    private val filesAccessDenied = AtomicLong(0)

    override fun beforeStep(stepExecution: StepExecution) {
        currentStepExecution = stepExecution
        currentJobRunId = stepExecution.executionContext.getLong(CrawlStepListener.JOB_RUN_ID_KEY, -1L)
            .takeIf { it > 0 }
        currentCrawlConfigId = stepExecution.jobExecution.jobParameters
            .getString(JobRunTrackingListener.CRAWL_CONFIG_ID_KEY)
            ?.toLongOrNull()
        log.info(
            "Writer initialized with jobRunId: {}, crawlConfigId: {}",
            currentJobRunId,
            currentCrawlConfigId
        )
    }

    override fun afterStep(stepExecution: StepExecution): org.springframework.batch.core.ExitStatus {
        // Flush atomic counters into step execution context (single-threaded at this point)
        val context = stepExecution.executionContext
        context.putLong("foldersDiscovered", foldersDiscovered.get())
        context.putLong("foldersNew", foldersNew.get())
        context.putLong("foldersUpdated", foldersUpdated.get())
        context.putLong("foldersSkipped", foldersSkipped.get())
        context.putLong("filesDiscovered", filesDiscovered.get())
        context.putLong("filesNew", filesNew.get())
        context.putLong("filesUpdated", filesUpdated.get())
        context.putLong("filesSkipped", filesSkipped.get())
        context.putLong("filesError", filesError.get())
        context.putLong("filesAccessDenied", filesAccessDenied.get())
        return org.springframework.batch.core.ExitStatus.COMPLETED
    }

    override fun write(chunk: Chunk<out CombinedCrawlResult>) {
        if (chunk.isEmpty()) {
            return
        }

        log.debug("Writing chunk of {} combined items", chunk.size())

        val folderDTOs = chunk.mapNotNull { it.folder }
        val allFileDTOs = chunk.flatMap { it.files }

        // Set stable ownership and last-run metadata before persistence.
        folderDTOs.forEach {
            it.jobRunId = currentJobRunId
            it.crawlConfigId = currentCrawlConfigId
        }
        allFileDTOs.forEach {
            it.jobRunId = currentJobRunId
            it.crawlConfigId = currentCrawlConfigId
        }

        // Persist folders first (files may reference them as parents)
        val foldersWritten = if (folderDTOs.isNotEmpty()) {
            writeFoldersBulk(folderDTOs)
        } else 0

        // Persist files
        val filesWritten = if (allFileDTOs.isNotEmpty()) {
            writeFilesBulk(allFileDTOs)
        } else 0

        log.info("Wrote chunk: {} folders, {} files", foldersWritten, filesWritten)

        // Issue #8: persist crawl checkpoint at end of each chunk so a crash
        // can resume from the last directory we successfully wrote. The reader
        // emits items in deterministic sorted order, so the last directory URI
        // in this chunk is the highest URI processed so far.
        if (checkpointService != null && currentCrawlConfigId != null) {
            val lastDirUri = chunk.asSequence()
                .mapNotNull { result -> lastDirectoryUriFor(result) }
                .lastOrNull()
            if (lastDirUri != null) {
                try {
                    checkpointService.upsert(currentCrawlConfigId!!, lastDirUri)
                    log.debug("Updated crawl checkpoint: configId={}, uri={}", currentCrawlConfigId, lastDirUri)
                } catch (e: Exception) {
                    log.warn("Failed to persist crawl checkpoint (configId={}, uri={}): {}",
                        currentCrawlConfigId, lastDirUri, e.message)
                }
            }
        }
    }

    /**
     * Derive the directory URI represented by a chunk result. Folder is non-null
     * for first-batch items; for continuation batches and unchanged folders we
     * fall back to a file's parent path (all files in a result share a parent).
     */
    private fun lastDirectoryUriFor(result: CombinedCrawlResult): String? {
        result.folder?.uri?.let { return it }
        val firstFile = result.files.firstOrNull() ?: return null
        return firstFile.uri?.let { uri ->
            val slash = uri.lastIndexOf('/')
            if (slash > 0) uri.substring(0, slash) else null
        }
    }

    private fun writeFoldersBulk(folderDTOs: List<FSFolderDTO>): Int {
        try {
            return writeFoldersBulkInternal(folderDTOs)
        } catch (e: Exception) {
            log.warn("Bulk folder save failed, falling back to per-item: {}", e.message)
            return writeFoldersPerItem(folderDTOs)
        }
    }

    private fun writeFoldersBulkInternal(folderDTOs: List<FSFolderDTO>): Int {
        val (newDTOs, existingDTOs) = folderDTOs.partition { it.id == null }

        val entities = mutableListOf<FSFolder>()

        // Map new DTOs to new entities
        for (dto in newDTOs) {
            val entity = FSFolder()
            folderMapper.updateFSFolder(dto, entity)
            entities.add(entity)
        }

        // Load existing entities in bulk and apply updates
        if (existingDTOs.isNotEmpty()) {
            val existingIds = existingDTOs.mapNotNull { it.id }
            val existingEntities = folderRepository.findAllById(existingIds).associateBy { it.id }

            for (dto in existingDTOs) {
                val entity = existingEntities[dto.id]
                if (entity != null) {
                    folderMapper.updateFSFolder(dto, entity)
                    entities.add(entity)
                } else {
                    log.warn("Folder not found for update, id={}, uri={}", dto.id, dto.uri)
                }
            }
        }

        val saved = folderRepository.saveAll(entities)

        // Track statistics
        var count = 0
        for (i in saved.indices) {
            val isNew = i < newDTOs.size
            val dto = if (isNew) newDTOs[i] else existingDTOs[i - newDTOs.size]
            incrementFolderCounter(isNew, dto.analysisStatus)
            count++
        }

        return count
    }

    private fun writeFoldersPerItem(folderDTOs: List<FSFolderDTO>): Int {
        var written = 0
        for (folder in folderDTOs) {
            try {
                val isNew = folder.id == null
                if (isNew) {
                    folderService.create(folder)
                } else {
                    folderService.update(folder.id!!, folder)
                }
                written++
                incrementFolderCounter(isNew, folder.analysisStatus)
            } catch (e: Exception) {
                log.error("Failed to save folder: {}", folder.uri, e)
            }
        }
        return written
    }

    private fun writeFilesBulk(fileDTOs: List<FSFileDTO>): Int {
        try {
            return writeFilesBulkInternal(fileDTOs)
        } catch (e: Exception) {
            log.warn("Bulk file save failed, falling back to per-item: {}", e.message)
            return writeFilesPerItem(fileDTOs)
        }
    }

    private fun writeFilesBulkInternal(fileDTOs: List<FSFileDTO>): Int {
        val (newDTOs, existingDTOs) = fileDTOs.partition { it.id == null }

        val entities = mutableListOf<FSFile>()
        // Files whose content was modified since last ingest — their existing
        // ContentChunks carry stale NLP annotations and must be re-processed
        // (issue #150). Collected here, reset after saveAll.
        val fileIdsNeedingNlpReset = mutableListOf<Long>()

        // Map new DTOs to new entities
        // Pass real folderRepository for mapper @AfterMapping; during crawl dto.fsFolder
        // is always null so findById is never called, but the mapper needs the reference.
        for (dto in newDTOs) {
            val entity = FSFile()
            fileMapper.updateFSFile(dto, entity, folderRepository)
            entities.add(entity)
        }

        // Load existing entities in bulk and apply updates
        if (existingDTOs.isNotEmpty()) {
            val existingIds = existingDTOs.mapNotNull { it.id }
            val existingEntities = fileRepository.findAllById(existingIds).associateBy { it.id }

            for (dto in existingDTOs) {
                val entity = existingEntities[dto.id]
                if (entity != null) {
                    if (isFileContentModified(entity, dto)) {
                        entity.id?.let { fileIdsNeedingNlpReset.add(it) }
                    }
                    fileMapper.updateFSFile(dto, entity, folderRepository)
                    entities.add(entity)
                } else {
                    log.warn("File not found for update, id={}, uri={}", dto.id, dto.uri)
                }
            }
        }

        val saved = fileRepository.saveAll(entities)

        resetStaleNlpForReingest(fileIdsNeedingNlpReset)

        // Track statistics
        var count = 0
        for (i in saved.indices) {
            val isNew = i < newDTOs.size
            val dto = if (isNew) newDTOs[i] else existingDTOs[i - newDTOs.size]
            incrementFileCounter(isNew, dto.analysisStatus)

            if (dto.accessDenied) incrementFileAccessDenied()
            if (dto.extractionError) incrementFileError()
            count++
        }

        return count
    }

    /**
     * True when the inbound DTO carries a different content signature than the
     * persisted entity — i.e. fsLastModified or size changed. Used to decide
     * whether existing ContentChunks need their NLP state reset (issue #150).
     *
     * Returns false if the new fsLastModified is null (no signal to act on) or
     * if both signals match — re-ingest of an unchanged file (e.g. LOCATE→INDEX
     * promotion path) leaves chunks alone since their text is unchanged.
     */
    private fun isFileContentModified(existing: FSFile, dto: FSFileDTO): Boolean {
        val newModified = dto.fsLastModified ?: return false
        val oldModified = existing.fsLastModified
        if (oldModified != null && newModified.isAfter(oldModified)) return true
        // size change with same or null mtime — also content-modified
        val newSize = dto.size
        val oldSize = existing.size
        if (newSize != null && oldSize != null && newSize != oldSize) return true
        return false
    }

    private fun resetStaleNlpForReingest(fileIds: List<Long>) {
        if (fileIds.isEmpty() || contentChunkRepository == null) return
        for (fileId in fileIds) {
            try {
                val reset = contentChunkRepository.resetNlpProcessedAtByConceptId(fileId)
                if (reset > 0) {
                    log.info("Re-ingest of FSFile {}: reset NLP on {} chunk(s) (issue #150)", fileId, reset)
                }
            } catch (e: Exception) {
                log.warn("Failed to reset NLP for re-ingested FSFile {}: {}", fileId, e.message)
            }
        }
    }

    private fun writeFilesPerItem(fileDTOs: List<FSFileDTO>): Int {
        var written = 0
        for (file in fileDTOs) {
            try {
                val isNew = file.id == null
                val needsNlpReset = !isNew && contentChunkRepository != null &&
                    fileRepository.findById(file.id!!).orElse(null)
                        ?.let { isFileContentModified(it, file) } == true
                if (isNew) {
                    fileService.create(file)
                } else {
                    fileService.update(file.id!!, file)
                }
                if (needsNlpReset) {
                    resetStaleNlpForReingest(listOf(file.id!!))
                }
                written++
                incrementFileCounter(isNew, file.analysisStatus)

                if (file.accessDenied) incrementFileAccessDenied()
                if (file.extractionError) incrementFileError()
            } catch (e: Exception) {
                log.error("Failed to save file: {}", file.uri, e)
                incrementFileError()
            }
        }
        return written
    }

    private fun incrementFolderCounter(isNew: Boolean, analysisStatus: com.oconeco.spring_search_tempo.base.domain.AnalysisStatus?) {
        foldersDiscovered.incrementAndGet()

        if (analysisStatus == com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.SKIP) {
            foldersSkipped.incrementAndGet()
        } else if (isNew) {
            foldersNew.incrementAndGet()
        } else {
            foldersUpdated.incrementAndGet()
        }
    }

    private fun incrementFileCounter(isNew: Boolean, analysisStatus: com.oconeco.spring_search_tempo.base.domain.AnalysisStatus?) {
        filesDiscovered.incrementAndGet()

        if (analysisStatus == com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.SKIP) {
            filesSkipped.incrementAndGet()
        } else if (isNew) {
            filesNew.incrementAndGet()
        } else {
            filesUpdated.incrementAndGet()
        }
    }

    private fun incrementFileError() {
        filesError.incrementAndGet()
    }

    private fun incrementFileAccessDenied() {
        filesAccessDenied.incrementAndGet()
    }
}
