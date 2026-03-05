package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.oconeco.spring_search_tempo.batch.fscrawl.JobRunTrackingListener
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemReader


/**
 * Reader that fetches new messages from an IMAP folder using UID-based incremental sync.
 *
 * Uses IMAP UIDs to efficiently fetch only messages newer than the last sync.
 * This is the "quick sync" strategy: SEARCH UID {lastSyncUid}:* returns only new messages.
 *
 * Supports:
 * - Incremental sync (default): Only fetch messages with UID > lastSyncUid
 * - Full sync (forceFullSync=true): Fetch all messages regardless of lastSyncUid
 * - UIDVALIDITY checking: Auto-reset to full sync if UIDVALIDITY changed
 * - Header prefetch: Batch downloads envelope/headers to reduce IMAP round-trips
 * - Batch duplicate detection: Single DB query to filter existing messages
 * - Heartbeat callback: Optional callback invoked during long-running initialization
 */
class EmailQuickSyncReader(
    private val account: EmailAccountDTO,
    private val folderName: String,
    private val imapConnectionService: ImapConnectionService,
    private val emailFolderService: EmailFolderService,
    private val emailMessageService: EmailMessageService,
    private val jobRunService: JobRunService,
    private val forceFullSync: Boolean = false
) : ItemReader<ImapMessageWrapper>, StepExecutionListener, AutoCloseable {

    companion object {
        private val log = LoggerFactory.getLogger(EmailQuickSyncReader::class.java)
    }

    private var store: Store? = null
    private var folder: IMAPFolder? = null
    private var messageWrappers: List<ImapMessageWrapper>? = null  // Pre-built with UIDs
    private var currentIndex = 0
    private var initialized = false
    private var highestUid: Long = 0
    private var totalFetched = 0
    private var duplicatesSkipped = 0
    private var jobRunId: Long? = null

    override fun beforeStep(stepExecution: StepExecution) {
        // Get jobRunId from execution context for heartbeat updates
        jobRunId = stepExecution.jobExecution.executionContext.getLong(
            JobRunTrackingListener.JOB_RUN_ID_KEY, -1L
        ).takeIf { it > 0 }
        log.debug("Reader initialized with jobRunId={}", jobRunId)
    }

    /**
     * Update heartbeat during long-running initialization.
     */
    private fun updateHeartbeat() {
        val id = jobRunId ?: return
        try {
            jobRunService.updateHeartbeat(id)
        } catch (e: Exception) {
            log.warn("Failed to update heartbeat: {}", e.message)
        }
    }

    override fun read(): ImapMessageWrapper? {
        if (!initialized) {
            initialize()
        }

        val wrappers = messageWrappers ?: return null
        if (currentIndex >= wrappers.size) {
            return null
        }

        val wrapper = wrappers[currentIndex++]

        // Track highest UID for sync state update
        if (wrapper.uid > highestUid) {
            highestUid = wrapper.uid
        }

        // Log progress every 500 messages
        if (currentIndex % 500 == 0) {
            log.info("[{}] Processing progress: {}/{} messages read from {}",
                account.email, currentIndex, wrappers.size, folderName)
        }

        return wrapper
    }

    /**
     * Get count of duplicates skipped during initialization.
     */
    fun getDuplicatesSkipped(): Int = duplicatesSkipped

    /**
     * Get total messages fetched from IMAP before duplicate filtering.
     */
    fun getTotalFetched(): Int = totalFetched

    private fun initialize() {
        initialized = true

        try {
            // Connect to IMAP server
            log.info("Connecting to IMAP for {} sync: {} / {}",
                if (forceFullSync) "FULL" else "quick",
                account.email, folderName)
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

            // Check UIDVALIDITY - if changed, UIDs are invalid and we must do full sync
            val currentUidValidity = folder!!.uidValidity
            val uidValidityChanged = emailFolderService.updateUidValidity(folderDto.id!!, currentUidValidity)
            if (uidValidityChanged) {
                log.warn("UIDVALIDITY changed for folder {} (was {}, now {}). Forcing full sync.",
                    folderName, folderDto.uidValidity, currentUidValidity)
            }

            // Determine effective lastUid
            val lastUid = when {
                forceFullSync -> {
                    log.info("Force full sync requested for folder {}", folderName)
                    0L
                }
                uidValidityChanged -> {
                    log.info("UIDVALIDITY changed, resetting to full sync for folder {}", folderName)
                    0L
                }
                else -> folderDto.lastSyncUid ?: 0L
            }

            // Fetch messages with UID > lastUid
            // PERFORMANCE: Always use getMessagesByUID() - this pre-caches UIDs and returns
            // messages in UID order, avoiding N individual getUID() calls during sort/read.
            // UIDFolder.LASTUID means "highest UID in folder"
            var rawMessages: Array<Message> = if (lastUid == 0L) {
                // Full sync: get all messages via UID range (1 to LASTUID)
                val isFirst = (folderDto.lastSyncUid ?: 0L) == 0L && !forceFullSync && !uidValidityChanged
                log.info("{} for folder {}, fetching all messages by UID range",
                    if (isFirst) "First sync" else "Full resync", folderName)
                folder!!.getMessagesByUID(1, UIDFolder.LASTUID)
            } else {
                // Incremental sync: only new messages
                log.info("Incremental sync for folder {} since UID {}", folderName, lastUid)
                folder!!.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID)
            }

            // Filter out null messages (deleted or inaccessible)
            rawMessages = rawMessages.filterNotNull().toTypedArray()
            totalFetched = rawMessages.size

            if (rawMessages.isEmpty()) {
                log.info("[{}] No messages to process in {}", account.email, folderName)
                messageWrappers = emptyList()
                return
            }

            log.info("[{}] Found {} messages in {}, starting header prefetch (this may take several minutes for large folders)...",
                account.email, rawMessages.size, folderName)

            // OPTIMIZATION: Prefetch headers in batches to show progress
            // For large folders, fetching all headers at once can take 10+ minutes with no feedback
            val fetchProfile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)      // From, To, Subject, Date
                add(FetchProfile.Item.CONTENT_INFO)  // Content-Type
                add("Message-ID")
                add("In-Reply-To")
                add("References")
            }

            val batchSize = 1000
            val startTime = System.currentTimeMillis()
            if (rawMessages.size <= batchSize) {
                // Small folder - fetch all at once
                folder!!.fetch(rawMessages, fetchProfile)
                updateHeartbeat()  // Update heartbeat after fetch
            } else {
                // Large folder - fetch in batches with progress logging and heartbeats
                var fetched = 0
                rawMessages.toList().chunked(batchSize).forEach { batch ->
                    folder!!.fetch(batch.toTypedArray(), fetchProfile)
                    fetched += batch.size
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    log.info("[{}] Header prefetch progress: {}/{} ({} sec elapsed)",
                        account.email, fetched, rawMessages.size, elapsed)
                    updateHeartbeat()  // Update heartbeat after each batch
                }
            }
            val totalElapsed = (System.currentTimeMillis() - startTime) / 1000
            log.info("[{}] Header prefetch complete for {} messages in {} seconds",
                account.email, rawMessages.size, totalElapsed)

            // OPTIMIZATION: Batch duplicate detection with single DB query
            // Collect all Message-IDs, query DB once, filter locally
            log.info("[{}] Running duplicate detection for {} messages...", account.email, rawMessages.size)
            val messageIdMap = mutableMapOf<String, Message>()
            val messagesWithoutId = mutableListOf<Message>()

            for (msg in rawMessages) {
                val msgId = msg.getHeader("Message-ID")?.firstOrNull()?.trim()
                if (msgId != null) {
                    messageIdMap[msgId] = msg
                } else {
                    // Messages without Message-ID are always processed
                    messagesWithoutId.add(msg)
                }
            }

            // Single DB query for all Message-IDs
            val existingIds = if (messageIdMap.isNotEmpty()) {
                emailMessageService.findExistingMessageIds(messageIdMap.keys)
            } else {
                emptySet()
            }

            duplicatesSkipped = existingIds.size
            log.info("[{}] Duplicate check complete: {} of {} have Message-ID, {} already in DB",
                account.email, messageIdMap.size, rawMessages.size, duplicatesSkipped)

            // Filter to only new messages
            val newMessages = messageIdMap.filterKeys { it !in existingIds }.values.toMutableList()
            newMessages.addAll(messagesWithoutId)

            // PERFORMANCE: Build ImapMessageWrapper list with UIDs in batch.
            // Since getMessagesByUID returns messages in UID order, no sorting needed.
            // Batch the getUID calls to minimize IMAP round-trips.
            log.info("[{}] Building message wrappers for {} messages...", account.email, newMessages.size)
            val wrapperStartTime = System.currentTimeMillis()

            messageWrappers = newMessages.map { msg ->
                ImapMessageWrapper(
                    message = msg,
                    uid = folder!!.getUID(msg),  // UIDs are cached from getMessagesByUID
                    folderName = folderName,
                    accountId = account.id!!
                )
            }

            val wrapperElapsed = System.currentTimeMillis() - wrapperStartTime
            log.info("[{}] Built {} message wrappers in {} ms",
                account.email, messageWrappers?.size ?: 0, wrapperElapsed)

            log.info(
                "Found {} new messages to process in {} ({} duplicates skipped, sync mode: {}, last UID: {})",
                messageWrappers?.size ?: 0,
                folderName,
                duplicatesSkipped,
                if (lastUid == 0L) "FULL" else "INCREMENTAL",
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
