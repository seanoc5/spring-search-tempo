package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter


/**
 * Writer that persists EmailMessage entities and updates sync state.
 *
 * Tracks statistics and updates folder sync state after processing.
 */
class EmailQuickSyncWriter(
    private val emailMessageService: EmailMessageService,
    private val emailFolderService: EmailFolderService,
    private val accountId: Long,
    private val folderName: String
) : ItemWriter<EmailMessageDTO>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(EmailQuickSyncWriter::class.java)
    }

    private var savedCount = 0
    private var skippedCount = 0
    private var errorCount = 0
    private var highestUid: Long = 0
    private var folderId: Long? = null
    private lateinit var stepExecution: StepExecution

    override fun beforeStep(stepExecution: StepExecution) {
        this.stepExecution = stepExecution

        // Initialize counters in execution context
        stepExecution.executionContext.putLong("emailsSaved", 0L)
        stepExecution.executionContext.putLong("emailsError", 0L)
        stepExecution.executionContext.putLong("emailsSkipped", 0L)

        // Get folder ID
        val folderDto = emailFolderService.findOrCreate(accountId, folderName, folderName)
        folderId = folderDto.id
    }

    override fun write(chunk: Chunk<out EmailMessageDTO>) {
        // PERFORMANCE: Batch insert new items, individual update for existing
        val newItems = mutableListOf<EmailMessageDTO>()
        val updateItems = mutableListOf<EmailMessageDTO>()

        chunk.items.forEach { dto ->
            // Set folder reference
            dto.emailFolder = folderId

            // Track highest UID for sync state update
            dto.imapUid?.let { uid ->
                if (uid > highestUid) highestUid = uid
            }

            if (dto.id == null) {
                newItems.add(dto)
            } else {
                updateItems.add(dto)
            }
        }

        // Bulk insert new items
        if (newItems.isNotEmpty()) {
            try {
                val ids = emailMessageService.createBulk(newItems)
                savedCount += ids.size
                log.debug("Bulk inserted {} emails", ids.size)
            } catch (e: Exception) {
                // Fallback to individual saves on bulk failure
                log.warn("Bulk insert failed, falling back to individual saves: {}", e.message)
                newItems.forEach { dto ->
                    try {
                        emailMessageService.create(dto)
                        savedCount++
                    } catch (ex: Exception) {
                        errorCount++
                        log.error("Error saving email {}: {}", dto.messageId ?: dto.uri, ex.message)
                    }
                }
            }
        }

        // Individual updates for existing items (rare during initial sync)
        updateItems.forEach { dto ->
            try {
                emailMessageService.update(dto.id!!, dto)
                savedCount++
            } catch (e: Exception) {
                errorCount++
                log.error("Error updating email {}: {}", dto.messageId ?: dto.uri, e.message)
            }
        }

        // Update counters
        stepExecution.executionContext.putLong("emailsSaved", savedCount.toLong())
        stepExecution.executionContext.putLong("emailsError", errorCount.toLong())

        if (savedCount % 500 == 0 && savedCount > 0) {
            log.info("Saved {} emails for {}/{}", savedCount, accountId, folderName)
        }
    }

    override fun afterStep(stepExecution: StepExecution): ExitStatus {
        // Store highest UID in execution context for job-level listener
        stepExecution.executionContext.putLong("highestUid", highestUid)

        // Update folder sync state with highest UID
        if (highestUid > 0 && folderId != null) {
            try {
                emailFolderService.updateSyncState(folderId!!, highestUid, savedCount.toLong())
                log.info(
                    "Updated sync state for folder {}: lastUid={}, messageCount={}",
                    folderName,
                    highestUid,
                    savedCount
                )
            } catch (e: Exception) {
                log.error("Failed to update folder sync state: {}", e.message, e)
            }
        }

        log.info(
            "Email write step completed for {}/{}: {} saved, {} skipped (duplicate), {} errors",
            accountId,
            folderName,
            savedCount,
            skippedCount,
            errorCount
        )

        return if (errorCount > savedCount) {
            ExitStatus.FAILED
        } else {
            ExitStatus.COMPLETED
        }
    }

    fun getSavedCount(): Int = savedCount
    fun getErrorCount(): Int = errorCount
    fun getHighestUid(): Long = highestUid
}
