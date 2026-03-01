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
        chunk.items.forEach { dto ->
            try {
                // Set folder reference
                dto.emailFolder = folderId

                // Check for duplicate URI (crash recovery / re-processing)
                if (dto.id == null && dto.uri != null && emailMessageService.existsByUri(dto.uri!!)) {
                    skippedCount++
                    log.debug("Skipping duplicate URI: {}", dto.uri)
                } else if (dto.id == null) {
                    emailMessageService.create(dto)
                    savedCount++
                } else {
                    emailMessageService.update(dto.id!!, dto)
                    savedCount++
                }

                // Track highest UID for sync state update
                dto.imapUid?.let { uid ->
                    if (uid > highestUid) highestUid = uid
                }

            } catch (e: Exception) {
                errorCount++
                log.error(
                    "Error saving email message {}: {}",
                    dto.messageId ?: dto.uri,
                    e.message,
                    e
                )
            }
        }

        // Update counters
        stepExecution.executionContext.putLong("emailsSaved", savedCount.toLong())
        stepExecution.executionContext.putLong("emailsError", errorCount.toLong())

        if (savedCount % 50 == 0 && savedCount > 0) {
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
