package com.oconeco.spring_search_tempo.batch.assignment

import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicLong

/**
 * Writer that persists analysis status assignments.
 *
 * Updates folders and files with:
 * - analysisStatus (the assigned status)
 * - analysisStatusReason (why this status was assigned)
 * - analysisStatusSetBy (PATTERN, INHERITED, DEFAULT)
 *
 * @param folderRepository Repository for folder updates
 * @param fileRepository Repository for file updates
 */
class AssignmentWriter(
    private val folderRepository: FSFolderRepository,
    private val fileRepository: FSFileRepository
) : ItemWriter<AssignmentResult>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(AssignmentWriter::class.java)
    }

    // Statistics
    private val foldersAssigned = AtomicLong(0)
    private val filesAssigned = AtomicLong(0)
    private val patternMatches = AtomicLong(0)
    private val inherited = AtomicLong(0)

    override fun afterStep(stepExecution: StepExecution): org.springframework.batch.core.ExitStatus {
        stepExecution.executionContext.putLong("foldersAssigned", foldersAssigned.get())
        stepExecution.executionContext.putLong("filesAssigned", filesAssigned.get())
        stepExecution.executionContext.putLong("patternMatches", patternMatches.get())
        stepExecution.executionContext.putLong("inherited", inherited.get())

        log.info(
            "Assignment complete: {} folders, {} files ({} pattern matches, {} inherited)",
            foldersAssigned.get(), filesAssigned.get(), patternMatches.get(), inherited.get()
        )

        return org.springframework.batch.core.ExitStatus.COMPLETED
    }

    @Transactional
    override fun write(chunk: Chunk<out AssignmentResult>) {
        chunk.items.forEach { result ->
            when (result.entityType) {
                "FOLDER" -> updateFolder(result)
                "FILE" -> updateFile(result)
            }

            // Track statistics
            when (result.setBy) {
                "PATTERN" -> patternMatches.incrementAndGet()
                "INHERITED" -> inherited.incrementAndGet()
            }
        }
    }

    private fun updateFolder(result: AssignmentResult) {
        val folder = folderRepository.findById(result.id).orElse(null) ?: run {
            log.warn("Folder {} not found for assignment", result.id)
            return
        }

        folder.analysisStatus = result.newStatus
        folder.analysisStatusReason = result.reason
        folder.analysisStatusSetBy = result.setBy

        folderRepository.save(folder)
        foldersAssigned.incrementAndGet()

        log.debug("Assigned folder {}: {} ({})", folder.uri, result.newStatus, result.setBy)
    }

    private fun updateFile(result: AssignmentResult) {
        val file = fileRepository.findById(result.id).orElse(null) ?: run {
            log.warn("File {} not found for assignment", result.id)
            return
        }

        file.analysisStatus = result.newStatus
        file.analysisStatusReason = result.reason
        file.analysisStatusSetBy = result.setBy

        fileRepository.save(file)
        filesAssigned.incrementAndGet()

        log.debug("Assigned file {}: {} ({})", file.uri, result.newStatus, result.setBy)
    }
}
