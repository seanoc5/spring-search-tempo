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
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemProcessor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe processor for parallel body enrichment.
 *
 * Uses ThreadLocal for per-thread IMAP connection state, enabling safe
 * parallel processing with TaskExecutor or AsyncItemProcessor.
 *
 * Each processing thread maintains its own IMAP connection that is
 * reused for messages in the same folder and cleaned up at step end.
 */
class ThreadSafeBodyEnrichmentProcessor(
    private val imapConnectionService: ImapConnectionService,
    private val emailAccountService: EmailAccountService,
    private val emailFolderService: EmailFolderService,
    private val emailTextExtractionService: EmailTextExtractionService,
    private val objectMapper: ObjectMapper = ObjectMapper()
) : ItemProcessor<EmailMessageDTO, BodyEnrichmentResult>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(ThreadSafeBodyEnrichmentProcessor::class.java)
    }

    // Thread-safe counters
    private val processedCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)

    /**
     * Per-thread connection state holding IMAP store and folder.
     */
    data class ConnectionState(
        var store: Store? = null,
        var folder: IMAPFolder? = null,
        var folderId: Long? = null
    ) {
        fun close() {
            try {
                folder?.close(false)
            } catch (e: Exception) {
                log.warn("Error closing IMAP folder: {}", e.message)
            }
            try {
                store?.close()
            } catch (e: Exception) {
                log.warn("Error closing IMAP store: {}", e.message)
            }
            folder = null
            store = null
            folderId = null
        }
    }

    // ThreadLocal for per-thread connection state
    private val threadConnections = ThreadLocal.withInitial { ConnectionState() }

    // Track all connection states for cleanup
    private val allConnectionStates = ConcurrentHashMap<Long, ConnectionState>()

    override fun process(item: EmailMessageDTO): BodyEnrichmentResult? {
        val messageId = item.id ?: return null
        val folderId = item.emailFolder ?: run {
            log.warn("Message {} has no folder, skipping body fetch", messageId)
            return null
        }

        val threadId = Thread.currentThread().id
        val connState = threadConnections.get()

        // Register this thread's connection state for cleanup
        allConnectionStates.putIfAbsent(threadId, connState)

        try {
            // Switch IMAP connection if folder changed for this thread
            if (folderId != connState.folderId) {
                connState.close()
                openConnection(connState, item.emailAccount!!, folderId)
                connState.folderId = folderId
            }

            val folder = connState.folder ?: run {
                log.error("No IMAP folder connection for message {} on thread {}", messageId, threadId)
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
                errorCount.incrementAndGet()
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

            val count = processedCount.incrementAndGet()
            if (count % 50 == 0) {
                log.info("[Thread {}] Fetched {} email bodies ({} errors)",
                    Thread.currentThread().name, count, errorCount.get())
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
            log.error("[Thread {}] Error fetching body for message {}: {}",
                Thread.currentThread().name, messageId, e.message, e)
            errorCount.incrementAndGet()
            return null
        }
    }

    private fun openConnection(connState: ConnectionState, accountId: Long, folderId: Long) {
        try {
            val account = emailAccountService.get(accountId)
            val folder = emailFolderService.get(folderId)

            log.debug("[Thread {}] Opening IMAP connection for account {} folder {}",
                Thread.currentThread().name, account.email, folder.folderName)

            connState.store = imapConnectionService.connect(account)
            connState.folder = connState.store!!.getFolder(folder.folderName) as IMAPFolder
            connState.folder!!.open(Folder.READ_ONLY)

        } catch (e: Exception) {
            log.error("[Thread {}] Failed to open IMAP connection: {}",
                Thread.currentThread().name, e.message, e)
            connState.close()
            throw e
        }
    }

    override fun afterStep(stepExecution: StepExecution): ExitStatus {
        // Close ALL thread connections
        log.info("Closing {} IMAP connections from parallel processing", allConnectionStates.size)
        allConnectionStates.values.forEach { it.close() }
        allConnectionStates.clear()
        threadConnections.remove()

        log.info("Thread-safe body enrichment complete: {} processed, {} errors",
            processedCount.get(), errorCount.get())
        return ExitStatus.COMPLETED
    }

    fun getProcessedCount(): Int = processedCount.get()
    fun getErrorCount(): Int = errorCount.get()
}
