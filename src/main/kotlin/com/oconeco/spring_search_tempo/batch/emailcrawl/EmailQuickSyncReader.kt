package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemReader


/**
 * Reader that fetches new messages from an IMAP folder using UID-based incremental sync.
 *
 * Uses IMAP UIDs to efficiently fetch only messages newer than the last sync.
 * This is the "quick sync" strategy: SEARCH UID {lastSyncUid}:* returns only new messages.
 */
class EmailQuickSyncReader(
    private val account: EmailAccountDTO,
    private val folderName: String,
    private val imapConnectionService: ImapConnectionService,
    private val emailFolderService: EmailFolderService
) : ItemReader<ImapMessageWrapper>, AutoCloseable {

    companion object {
        private val log = LoggerFactory.getLogger(EmailQuickSyncReader::class.java)
    }

    private var store: Store? = null
    private var folder: IMAPFolder? = null
    private var messages: Array<Message>? = null
    private var currentIndex = 0
    private var initialized = false
    private var highestUid: Long = 0

    override fun read(): ImapMessageWrapper? {
        if (!initialized) {
            initialize()
        }

        val msgs = messages ?: return null
        if (currentIndex >= msgs.size) {
            return null
        }

        val message = msgs[currentIndex++]
        val uid = folder!!.getUID(message)

        // Track highest UID for sync state update
        if (uid > highestUid) {
            highestUid = uid
        }

        return ImapMessageWrapper(
            message = message,
            uid = uid,
            folderName = folderName,
            accountId = account.id!!
        )
    }

    private fun initialize() {
        initialized = true

        try {
            // Connect to IMAP server
            log.info("Connecting to IMAP for quick sync: {} / {}", account.email, folderName)
            store = imapConnectionService.connect(account)

            // Open folder
            folder = store!!.getFolder(folderName) as IMAPFolder
            folder!!.open(Folder.READ_ONLY)

            // Get or create folder record
            val folderDto = emailFolderService.findOrCreate(
                account.id!!,
                folderName,
                folder!!.fullName
            )

            val lastUid = folderDto.lastSyncUid ?: 0L

            // Fetch messages with UID > lastUid
            // UIDFolder.LASTUID means "highest UID in folder"
            messages = if (lastUid == 0L) {
                // First sync: get all messages (could be a lot!)
                log.info("First sync for folder {}, fetching all messages", folderName)
                folder!!.messages
            } else {
                // Incremental sync: only new messages
                log.info("Incremental sync for folder {} since UID {}", folderName, lastUid)
                folder!!.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID)
            }

            // Filter out null messages (deleted or inaccessible)
            messages = messages?.filterNotNull()?.toTypedArray()

            log.info(
                "Found {} messages to process in {} (last UID: {})",
                messages?.size ?: 0,
                folderName,
                lastUid
            )

        } catch (e: Exception) {
            log.error("Failed to initialize email reader for {}/{}: {}", account.email, folderName, e.message, e)
            throw e
        }
    }

    /**
     * Get the highest UID processed (for sync state update).
     */
    fun getHighestUid(): Long = highestUid

    /**
     * Get the folder ID (for sync state update).
     */
    fun getFolderDto() = emailFolderService.findOrCreate(
        account.id!!,
        folderName,
        folder?.fullName ?: folderName
    )

    override fun close() {
        try {
            folder?.close(false)
        } catch (e: Exception) {
            log.warn("Error closing folder: {}", e.message)
        }
        try {
            store?.close()
        } catch (e: Exception) {
            log.warn("Error closing store: {}", e.message)
        }
        log.debug("Closed IMAP connection for {}/{}", account.email, folderName)
    }
}

/**
 * Wrapper for IMAP message with metadata needed for processing.
 */
data class ImapMessageWrapper(
    val message: Message,
    val uid: Long,
    val folderName: String,
    val accountId: Long
)
