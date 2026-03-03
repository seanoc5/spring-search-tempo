package com.oconeco.spring_search_tempo.batch.discovery

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * Writer for the discovery phase.
 *
 * Persists discovered folders and files with:
 * - Basic metadata (size, timestamps, permissions)
 * - locatedAt timestamp
 * - skipDetected flag
 * - analysisStatusSetBy = "DEFAULT" (not yet assigned by patterns)
 *
 * Uses upsert logic: update existing records or create new ones.
 *
 * @param folderService Service for folder persistence
 * @param fileService Service for file persistence
 * @param folderRepository Repository for folder lookup
 * @param fileRepository Repository for file lookup
 */
class DiscoveryWriter(
    private val folderService: FSFolderService,
    private val fileService: FSFileService,
    private val folderRepository: FSFolderRepository,
    private val fileRepository: FSFileRepository
) : ItemWriter<DiscoveryResult>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(DiscoveryWriter::class.java)
    }

    private var jobRunId: Long? = null

    // Statistics
    private val foldersNew = AtomicLong(0)
    private val foldersUpdated = AtomicLong(0)
    private val filesNew = AtomicLong(0)
    private val filesUpdated = AtomicLong(0)

    override fun beforeStep(stepExecution: StepExecution) {
        jobRunId = stepExecution.jobExecution.executionContext
            .getLong("jobRunId", -1L)
            .takeIf { it > 0 }
        log.info("DiscoveryWriter initialized with jobRunId: {}", jobRunId)
    }

    override fun afterStep(stepExecution: StepExecution): org.springframework.batch.core.ExitStatus {
        // Write statistics to step execution context
        stepExecution.executionContext.putLong("foldersNew", foldersNew.get())
        stepExecution.executionContext.putLong("foldersUpdated", foldersUpdated.get())
        stepExecution.executionContext.putLong("filesNew", filesNew.get())
        stepExecution.executionContext.putLong("filesUpdated", filesUpdated.get())

        log.info(
            "Discovery write complete: {} new folders, {} updated folders, {} new files, {} updated files",
            foldersNew.get(), foldersUpdated.get(), filesNew.get(), filesUpdated.get()
        )

        return org.springframework.batch.core.ExitStatus.COMPLETED
    }

    override fun write(chunk: Chunk<out DiscoveryResult>) {
        val now = OffsetDateTime.now()

        chunk.items.forEach { result ->
            // Process folder
            result.folder?.let { folder ->
                writeFolder(folder, now)
            }

            // Process files
            result.files.forEach { file ->
                writeFile(file, now)
            }
        }
    }

    /**
     * Write or update a discovered folder.
     */
    private fun writeFolder(folder: DiscoveredFolder, now: OffsetDateTime) {
        val existing = folderRepository.findByUri(folder.uri)

        if (existing != null) {
            // Update existing folder
            val dto = FSFolderDTO().apply {
                id = existing.id
                uri = folder.uri
                label = folder.label
                size = folder.size
                crawlDepth = folder.crawlDepth
                fsLastModified = folder.fsLastModified
                owner = folder.owner
                group = folder.group
                permissions = folder.permissions
                status = Status.CURRENT
                jobRunId = this@DiscoveryWriter.jobRunId
                // Set discovery fields
                locatedAt = now
                skipDetected = folder.skipDetected
                // Only set analysisStatus if skipDetected (preserve existing otherwise)
                if (folder.skipDetected) {
                    analysisStatus = AnalysisStatus.SKIP
                    analysisStatusReason = "PATTERN: ${folder.matchedPattern}"
                    analysisStatusSetBy = "PATTERN"
                }
            }
            folderService.update(existing.id!!, dto)
            foldersUpdated.incrementAndGet()
        } else {
            // Create new folder
            val dto = FSFolderDTO().apply {
                uri = folder.uri
                label = folder.label
                size = folder.size
                crawlDepth = folder.crawlDepth
                fsLastModified = folder.fsLastModified
                owner = folder.owner
                group = folder.group
                permissions = folder.permissions
                status = Status.NEW
                jobRunId = this@DiscoveryWriter.jobRunId
                // Set discovery fields
                locatedAt = now
                skipDetected = folder.skipDetected
                if (folder.skipDetected) {
                    analysisStatus = AnalysisStatus.SKIP
                    analysisStatusReason = "PATTERN: ${folder.matchedPattern}"
                    analysisStatusSetBy = "PATTERN"
                } else {
                    // Default to LOCATE until assignment phase
                    analysisStatus = AnalysisStatus.LOCATE
                    analysisStatusSetBy = "DEFAULT"
                }
            }
            folderService.create(dto)
            foldersNew.incrementAndGet()
        }
    }

    /**
     * Write or update a discovered file.
     */
    private fun writeFile(file: DiscoveredFile, now: OffsetDateTime) {
        val existing = fileRepository.findByUri(file.uri)
        val parentFolder = folderRepository.findByUri(file.parentUri)

        if (existing != null) {
            // Update existing file
            val dto = FSFileDTO().apply {
                id = existing.id
                uri = file.uri
                label = file.label
                size = file.size
                crawlDepth = file.crawlDepth
                fsLastModified = file.fsLastModified
                owner = file.owner
                group = file.group
                permissions = file.permissions
                status = Status.CURRENT
                jobRunId = this@DiscoveryWriter.jobRunId
                fsFolder = parentFolder?.id
                // Set discovery fields
                locatedAt = now
                skipDetected = file.parentSkipDetected
                // If parent is SKIP, mark file as SKIP too
                if (file.parentSkipDetected) {
                    analysisStatus = AnalysisStatus.SKIP
                    analysisStatusReason = "INHERITED: parent folder"
                    analysisStatusSetBy = "INHERITED"
                }
            }
            fileService.update(existing.id!!, dto)
            filesUpdated.incrementAndGet()
        } else {
            // Create new file
            val dto = FSFileDTO().apply {
                uri = file.uri
                label = file.label
                size = file.size
                crawlDepth = file.crawlDepth
                fsLastModified = file.fsLastModified
                owner = file.owner
                group = file.group
                permissions = file.permissions
                status = Status.NEW
                jobRunId = this@DiscoveryWriter.jobRunId
                fsFolder = parentFolder?.id
                // Set discovery fields
                locatedAt = now
                skipDetected = file.parentSkipDetected
                if (file.parentSkipDetected) {
                    analysisStatus = AnalysisStatus.SKIP
                    analysisStatusReason = "INHERITED: parent folder"
                    analysisStatusSetBy = "INHERITED"
                } else {
                    // Default to LOCATE until assignment phase
                    analysisStatus = AnalysisStatus.LOCATE
                    analysisStatusSetBy = "DEFAULT"
                }
            }
            fileService.create(dto)
            filesNew.incrementAndGet()
        }
    }
}
