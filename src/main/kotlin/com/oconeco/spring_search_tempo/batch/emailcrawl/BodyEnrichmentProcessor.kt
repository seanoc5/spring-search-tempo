package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.service.EmailTextExtractionService
import com.oconeco.spring_search_tempo.base.service.EmailTextResult
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.Folder
import jakarta.mail.Multipart
import jakarta.mail.Store
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemProcessor


/**
 * Pass 2 Processor: Fetches email bodies from IMAP.
 *
 * This is the slow pass that fetches full message content. It can be parallelized
 * using Spring Batch's AsyncItemProcessor wrapper.
 *
 * Maintains IMAP connections per folder for efficiency within a chunk.
 * Connections are closed when switching folders or at step end.
 */
class BodyEnrichmentProcessor(
    private val imapConnectionService: ImapConnectionService,
    private val emailAccountService: EmailAccountService,
    private val emailFolderService: EmailFolderService,
    private val emailTextExtractionService: EmailTextExtractionService,
    private val objectMapper: ObjectMapper = ObjectMapper()
) : ItemProcessor<EmailMessageDTO, BodyEnrichmentResult>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(BodyEnrichmentProcessor::class.java)
    }

    private var processedCount = 0
    private var errorCount = 0

    // Connection caching per folder
    private var currentStore: Store? = null
    private var currentFolder: IMAPFolder? = null
    private var currentFolderId: Long? = null

    override fun process(item: EmailMessageDTO): BodyEnrichmentResult? {
        val messageId = item.id ?: return null
        val folderId = item.emailFolder ?: run {
            log.warn("Message {} has no folder, skipping body fetch", messageId)
            return null
        }

        try {
            // Switch IMAP connection if folder changed
            if (folderId != currentFolderId) {
                closeCurrentConnection()
                openConnection(item.emailAccount!!, folderId)
                currentFolderId = folderId
            }

            val folder = currentFolder ?: run {
                log.error("No IMAP folder connection for message {}", messageId)
                return null
            }

            // Fetch message by UID
            val uid = item.imapUid ?: run {
                log.warn("Message {} has no IMAP UID, skipping", messageId)
                return null
            }

            val message = folder.getMessageByUID(uid)
            if (message == null) {
                log.warn("Message UID {} not found in IMAP folder, may have been deleted", uid)
                errorCount++
                return null
            }

            // Extract body text
            var bodyText: String? = null
            var bodySize: Long? = null

            when (val result = emailTextExtractionService.extractText(message)) {
                is EmailTextResult.Success -> {
                    bodyText = result.text
                    bodySize = result.text.length.toLong()
                }
                is EmailTextResult.Failure -> {
                    bodyText = "[Extraction failed: ${result.error}]"
                    bodySize = 0
                    log.warn("Body extraction failed for message {}: {}", messageId, result.error)
                }
            }

            // Extract attachment info
            var hasAttachments = false
            var attachmentCount = 0
            var attachmentNames: String? = null

            try {
                if (message.content is Multipart) {
                    val attachments = emailTextExtractionService.extractAttachmentInfo(message)
                    hasAttachments = attachments.isNotEmpty()
                    attachmentCount = attachments.size
                    attachmentNames = if (attachments.isNotEmpty()) {
                        objectMapper.writeValueAsString(attachments)
                    } else null
                }
            } catch (e: Exception) {
                log.warn("Failed to extract attachments for message {}: {}", messageId, e.message)
            }

            processedCount++
            if (processedCount % 50 == 0) {
                log.info("Fetched {} email bodies ({} errors)", processedCount, errorCount)
            }

            return BodyEnrichmentResult(
                messageId = messageId,
                bodyText = bodyText,
                bodySize = bodySize,
                hasAttachments = hasAttachments,
                attachmentCount = attachmentCount,
                attachmentNames = attachmentNames
            )

        } catch (e: Exception) {
            log.error("Error fetching body for message {}: {}", messageId, e.message, e)
            errorCount++
            return null
        }
    }

    private fun openConnection(accountId: Long, folderId: Long) {
        try {
            val account = emailAccountService.get(accountId)
            val folder = emailFolderService.get(folderId)

            log.debug("Opening IMAP connection for account {} folder {}", account.email, folder.folderName)

            currentStore = imapConnectionService.connect(account)
            currentFolder = currentStore!!.getFolder(folder.folderName) as IMAPFolder
            currentFolder!!.open(Folder.READ_ONLY)

        } catch (e: Exception) {
            log.error("Failed to open IMAP connection: {}", e.message, e)
            closeCurrentConnection()
            throw e
        }
    }

    private fun closeCurrentConnection() {
        try {
            currentFolder?.close(false)
        } catch (e: Exception) {
            log.warn("Error closing IMAP folder: {}", e.message)
        }
        try {
            currentStore?.close()
        } catch (e: Exception) {
            log.warn("Error closing IMAP store: {}", e.message)
        }
        currentFolder = null
        currentStore = null
        currentFolderId = null
    }

    override fun afterStep(stepExecution: StepExecution): org.springframework.batch.core.ExitStatus {
        closeCurrentConnection()
        log.info("Body enrichment complete: {} processed, {} errors", processedCount, errorCount)
        return org.springframework.batch.core.ExitStatus.COMPLETED
    }

    fun getProcessedCount(): Int = processedCount
    fun getErrorCount(): Int = errorCount
}


/**
 * Result of body enrichment for a single message.
 */
data class BodyEnrichmentResult(
    val messageId: Long,
    val bodyText: String?,
    val bodySize: Long?,
    val hasAttachments: Boolean,
    val attachmentCount: Int,
    val attachmentNames: String?
)
