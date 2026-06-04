package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Store
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemReader
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort


/**
 * Pass 2 Reader (default): loads DTOs needing body fetch, then bulk-prefetches
 * their bodies from IMAP via [FetchProfile] in batches of [prefetchBatchSize].
 *
 * Per-message [IMAPFolder.getMessageByUID] + [Message.getContent] triggers one
 * IMAP `FETCH RFC822` round-trip per body. On high-latency hosts (Gmail) that
 * lands at ~1 sec/message — see issue #58. Batching the FETCH via
 * [FetchProfile] with [IMAPFolder.FetchProfileItem.MESSAGE] pulls ~50 bodies
 * per round-trip, eliminating that latency tax.
 *
 * Lifecycle:
 *  1. First [read]: paginate DTOs from DB, group by `(accountId, folderId)`.
 *  2. As [read] consumes the prefetch window, refill: open the next folder if
 *     needed, take the next [prefetchBatchSize] DTOs from that folder, run
 *     [IMAPFolder.fetch], enqueue `(dto, message)` wrappers.
 *  3. When a folder is exhausted, close it before opening the next.
 *  4. [afterStep] closes any still-open IMAP resources.
 *
 * Memory: at most [prefetchBatchSize] fully-fetched IMAP `Message` bodies
 * are resident per folder window — the dominant cost. The DTO list itself
 * is loaded up front (O(N) small objects, ~200B each); at 20K backlog this
 * is a few MB and the message-body window dwarfs it. If multi-million
 * backlogs become realistic the DTO load can be made streaming, but for
 * the issue #58 target shape the bottleneck is IMAP latency, not heap.
 */
class PrefetchingBodyEnrichmentReader(
    private val imapConnectionService: ImapConnectionService,
    private val emailAccountService: EmailAccountService,
    private val emailFolderService: EmailFolderService,
    private val emailMessageService: EmailMessageService,
    private val accountId: Long,
    private val pageSize: Int = 100,
    private val prefetchBatchSize: Int = 50
) : ItemReader<PrefetchedBodyMessage>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(PrefetchingBodyEnrichmentReader::class.java)
    }

    private var initialized = false

    private val foldersToProcess: ArrayDeque<FolderWorkItem> = ArrayDeque()
    private var currentStore: Store? = null
    private var currentFolder: IMAPFolder? = null
    private var currentFolderWork: FolderWorkItem? = null

    private val prefetchedQueue: ArrayDeque<PrefetchedBodyMessage> = ArrayDeque()
    private var totalEmitted = 0
    private var prefetchRoundTrips = 0

    override fun beforeStep(stepExecution: StepExecution) {
        // No-op; lazy init on first read() so any DB errors surface as
        // step failures rather than job-launch failures.
    }

    override fun read(): PrefetchedBodyMessage? {
        if (!initialized) {
            initialize()
        }

        while (prefetchedQueue.isEmpty()) {
            if (!refillFromCurrentFolder()) {
                if (!advanceToNextFolder()) {
                    return null
                }
            }
        }

        totalEmitted++
        return prefetchedQueue.removeFirst()
    }

    private fun initialize() {
        initialized = true

        val count = emailMessageService.countHeadersOnlyByAccount(accountId)
        log.info(
            "PrefetchingBodyEnrichmentReader: {} messages need body fetch for account {} (prefetch batch={})",
            count, accountId, prefetchBatchSize
        )

        if (count == 0L) return

        val allMessages = mutableListOf<EmailMessageDTO>()
        var page = 0
        var hasMore = true
        while (hasMore) {
            val pageable = PageRequest.of(page, pageSize, Sort.by("emailFolder", "imapUid"))
            val pageResult = emailMessageService.findHeadersOnlyByAccount(accountId, pageable)
            allMessages.addAll(pageResult.content)
            hasMore = pageResult.hasNext()
            page++
        }

        // Group by folder so we open each folder exactly once and prefetch
        // in dense batches per round-trip.
        allMessages
            .filter { it.emailFolder != null && it.imapUid != null }
            .groupBy { it.emailFolder!! }
            .forEach { (folderId, dtos) ->
                foldersToProcess.addLast(
                    FolderWorkItem(
                        folderId = folderId,
                        pending = ArrayDeque(dtos)
                    )
                )
            }

        log.info(
            "Reader grouped {} body-fetch DTOs into {} folder batches for account {}",
            allMessages.size, foldersToProcess.size, accountId
        )
    }

    /**
     * Pull up to [prefetchBatchSize] more messages from the active folder, run
     * the bulk FETCH, and enqueue wrappers. Returns true if anything was
     * enqueued (i.e. the caller can keep reading from the same folder).
     */
    private fun refillFromCurrentFolder(): Boolean {
        val work = currentFolderWork ?: return false
        val folder = currentFolder ?: return false

        if (work.pending.isEmpty()) return false

        val batch = ArrayList<EmailMessageDTO>(prefetchBatchSize)
        while (batch.size < prefetchBatchSize && work.pending.isNotEmpty()) {
            batch.add(work.pending.removeFirst())
        }

        val uids = LongArray(batch.size) { batch[it].imapUid!! }
        val messages: Array<Message?> = folder.getMessagesByUID(uids)

        // FetchProfileItem.MESSAGE pulls the entire RFC822 body in one
        // round-trip per batch (and CONTENT_INFO covers content-type so
        // multipart detection doesn't trigger an extra fetch).
        val profile = FetchProfile().apply {
            add(IMAPFolder.FetchProfileItem.MESSAGE)
            add(FetchProfile.Item.CONTENT_INFO)
        }

        val nonNullMessages = messages.filterNotNull().toTypedArray()
        if (nonNullMessages.isNotEmpty()) {
            val started = System.currentTimeMillis()
            folder.fetch(nonNullMessages, profile)
            prefetchRoundTrips++
            val elapsed = System.currentTimeMillis() - started
            log.debug(
                "Prefetched {} bodies from folder {} in {} ms ({} round-trip(s) so far)",
                nonNullMessages.size, work.folderId, elapsed, prefetchRoundTrips
            )
        }

        for (i in batch.indices) {
            val dto = batch[i]
            val msg = messages[i]
            if (msg == null) {
                log.warn(
                    "Message UID {} not found in IMAP folder {} (may have been deleted), skipping",
                    dto.imapUid, work.folderId
                )
                continue
            }
            prefetchedQueue.addLast(PrefetchedBodyMessage(dto = dto, message = msg))
        }

        return prefetchedQueue.isNotEmpty()
    }

    /**
     * Close the current folder/store and open the next folder's IMAP
     * connection. Returns false when no more folders remain.
     */
    private fun advanceToNextFolder(): Boolean {
        // If the current folder still has pending DTOs, do NOT advance — let
        // the next refillFromCurrentFolder() handle them. (Defensive: this
        // shouldn't be hit because we only advance when the queue is empty
        // and the folder has been drained.)
        currentFolderWork?.let {
            if (it.pending.isNotEmpty()) return true
        }

        closeCurrentConnection()

        while (foldersToProcess.isNotEmpty()) {
            val next = foldersToProcess.removeFirst()
            if (next.pending.isEmpty()) continue

            try {
                openFolder(next)
                currentFolderWork = next
                return true
            } catch (e: Exception) {
                log.error(
                    "Failed to open IMAP folder id={} for prefetch, dropping {} pending bodies: {}",
                    next.folderId, next.pending.size, e.message, e
                )
                // Move on to the next folder rather than failing the whole
                // step — sibling folders still get a shot.
                closeCurrentConnection()
            }
        }
        return false
    }

    private fun openFolder(work: FolderWorkItem) {
        val account = emailAccountService.get(accountId)
        val folderDto = emailFolderService.get(work.folderId)
        log.info(
            "Opening IMAP folder for prefetch: account={} folder={} ({} pending)",
            account.email, folderDto.folderName, work.pending.size
        )
        currentStore = imapConnectionService.connect(account)
        currentFolder = currentStore!!.getFolder(folderDto.folderName) as IMAPFolder
        currentFolder!!.open(Folder.READ_ONLY)
    }

    private fun closeCurrentConnection() {
        try {
            currentFolder?.close(false)
        } catch (e: Exception) {
            log.warn("Error closing IMAP folder during prefetch teardown: {}", e.message)
        }
        try {
            currentStore?.close()
        } catch (e: Exception) {
            log.warn("Error closing IMAP store during prefetch teardown: {}", e.message)
        }
        currentFolder = null
        currentStore = null
        currentFolderWork = null
    }

    override fun afterStep(stepExecution: StepExecution): ExitStatus {
        closeCurrentConnection()
        log.info(
            "PrefetchingBodyEnrichmentReader complete: {} messages emitted across {} prefetch round-trip(s)",
            totalEmitted, prefetchRoundTrips
        )
        return stepExecution.exitStatus
    }

    fun getPrefetchRoundTrips(): Int = prefetchRoundTrips
    fun getEmittedCount(): Int = totalEmitted

    private data class FolderWorkItem(
        val folderId: Long,
        val pending: ArrayDeque<EmailMessageDTO>
    )
}


/**
 * Pairs a DTO awaiting body enrichment with the IMAP [Message] handle whose
 * body has already been bulk-fetched via [FetchProfile]. The downstream
 * processor extracts text/attachments from the cached message without
 * additional IMAP round-trips.
 *
 * Valid only while the owning [IMAPFolder] (managed by
 * [PrefetchingBodyEnrichmentReader]) is open — i.e. for the lifetime of the
 * batch step.
 */
data class PrefetchedBodyMessage(
    val dto: EmailMessageDTO,
    val message: Message
)
