package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.oconeco.spring_search_tempo.base.service.MirrorCheckpointService
import com.oconeco.spring_search_tempo.base.service.MirrorFolderProgressService
import com.oconeco.spring_search_tempo.batch.mirror.MirrorJobLifecycleListener.Companion.JOB_RUN_ID_KEY
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemReader
import java.util.ArrayDeque

/**
 * Spring Batch `ItemReader` that walks every enabled folder mapping in a
 * `MirrorConfig`, enumerating source UIDs in ascending order and emitting
 * one `MirrorTask` per UID that still needs copying.
 *
 * Lifecycle:
 *  - [open] connects once to the source IMAP store, applies the
 *    [MirrorCheckpoint] resume marker (if present) to determine the
 *    starting folder + UID, and loads the list of folder mappings.
 *  - [read] returns the next pending [MirrorTask], advancing across
 *    folders as each one is exhausted.
 *  - [close] closes the source store.
 *
 * Per-folder pipeline:
 *  1. Open source folder read-only, list all UIDs.
 *  2. Filter to UIDs greater than the resume marker (only on the resume
 *     folder; subsequent folders start from the beginning).
 *  3. Batched FETCH of the `Message-ID` header for each remaining UID
 *     (single IMAP round-trip per batch).
 *  4. Skip any UID whose Message-ID already has a `MirroredMessage` row
 *     for this `mirrorConfigId`. This is an *efficiency* optimization;
 *     `ImapMirrorService.mirrorMessage(...)` is still the correctness
 *     boundary (it re-checks dedup inside its own transaction).
 *  5. Buffer the survivors and return them one at a time.
 *
 * Source connection is opened once and reused across folders, per the
 * acceptance criteria. The destination connection is *not* managed here —
 * `ImapMirrorService.mirrorMessage(...)` opens its own (one per call) and
 * that constraint is documented in the `## Decision` log for issue #24.
 */
class MirrorMessageReader(
    mirrorConfigId: Long,
    private val mirrorConfigService: MirrorConfigService,
    private val emailAccountService: EmailAccountService,
    private val imapConnectionService: ImapConnectionService,
    private val mirroredMessageRepository: MirroredMessageRepository,
    private val checkpointService: MirrorCheckpointService,
    private val folderProgressService: MirrorFolderProgressService? = null,
    private val messageIdFetchBatchSize: Int = 500
) : ItemReader<MirrorTask>, StepExecutionListener {

    /**
     * Effective mirror config id for this step execution. Defaults to the
     * constructor-time value (used when wired directly in tests) and gets
     * overridden in [beforeStep] from `JobParameters` for production use,
     * which avoids relying on `@JobScope` proxy refresh between batches.
     */
    private var mirrorConfigId: Long = mirrorConfigId

    companion object {
        private val log = LoggerFactory.getLogger(MirrorMessageReader::class.java)
    }

    private var sourceStore: Store? = null
    private var mappings: List<FolderMapping> = emptyList()
    private var mappingIndex: Int = 0
    private var currentFolderName: String? = null
    private var currentDestFolder: String? = null
    private val pending: ArrayDeque<Long> = ArrayDeque()
    private var resumeFromUid: Long = 0L
    private var resumeFolder: String? = null
    private var resumeApplied: Boolean = false
    private var opened: Boolean = false

    /**
     * Pulled from [JobExecution.executionContext] in [beforeStep]; the
     * lifecycle listener stamps it under [JOB_RUN_ID_KEY] in `beforeJob`.
     * `null` means the reader was wired directly in a test that doesn't
     * exercise the JobRun lifecycle — folder-progress recording is then
     * a no-op (the dashboard test seeds rows directly).
     */
    private var jobRunId: Long? = null

    /**
     * Initialize state, open source store, and read the resume marker.
     * Called by [read] on first invocation so the reader can be wired
     * without needing `@StepScope`'s open/close callbacks.
     */
    private fun ensureOpen() {
        if (opened) return
        opened = true

        val config = mirrorConfigService.get(mirrorConfigId)
        mappings = config.folderMappings.filter { it.enabled }
        if (mappings.isEmpty()) {
            log.warn("MirrorJob has no enabled folder mappings for mirrorConfigId={}", mirrorConfigId)
            return
        }

        val sourceAccountId = config.sourceAccountId
            ?: throw IllegalStateException("MirrorConfig $mirrorConfigId has no sourceAccountId")
        val sourceAccount = emailAccountService.get(sourceAccountId)
        sourceStore = imapConnectionService.connect(sourceAccount)

        checkpointService.find(mirrorConfigId)?.let { cp ->
            resumeFolder = cp.currentFolder
            resumeFromUid = cp.lastSourceUidProcessed ?: 0L
            log.info(
                "Resuming MirrorJob: mirrorConfigId={} resumeFolder={} resumeFromUid={}",
                mirrorConfigId, resumeFolder, resumeFromUid
            )
            // Skip mappings whose source folder precedes the resume folder.
            val idx = mappings.indexOfFirst { it.source == resumeFolder }
            if (idx > 0) {
                mappingIndex = idx
            } else if (idx < 0) {
                // Checkpoint refers to a folder no longer in the mapping —
                // treat as a stale checkpoint and start from scratch.
                log.warn(
                    "Checkpoint folder '{}' not in current mappings for mirrorConfigId={}; ignoring resume marker",
                    resumeFolder, mirrorConfigId
                )
                resumeFolder = null
                resumeFromUid = 0L
            }
        }
    }

    override fun read(): MirrorTask? {
        ensureOpen()

        while (true) {
            if (pending.isNotEmpty()) {
                val uid = pending.poll()
                return MirrorTask(
                    mirrorConfigId = mirrorConfigId,
                    sourceFolder = currentFolderName!!,
                    destFolder = currentDestFolder!!,
                    sourceUid = uid
                )
            }
            // We exhausted the previous folder's pending queue; stamp it
            // complete on the progress dashboard before moving on so the
            // "folders complete" count advances in near-real-time.
            currentFolderName?.let { finished ->
                recordFolderCompletedSafely(finished)
            }
            if (mappingIndex >= mappings.size) return null
            advanceToNextFolder()
        }
    }

    private fun recordFolderOpenedSafely(
        sourceFolder: String,
        destFolder: String,
        totalConsidered: Long
    ) {
        val svc = folderProgressService ?: return
        val runId = jobRunId ?: return
        try {
            svc.recordFolderOpened(
                mirrorConfigId = mirrorConfigId,
                jobRunId = runId,
                sourceFolder = sourceFolder,
                destFolder = destFolder,
                totalConsidered = totalConsidered
            )
        } catch (e: Exception) {
            log.warn(
                "Failed to record folder-opened progress (jobRunId={}, folder='{}'): {}",
                runId, sourceFolder, e.message
            )
        }
    }

    private fun recordFolderCompletedSafely(sourceFolder: String) {
        val svc = folderProgressService ?: return
        val runId = jobRunId ?: return
        try {
            svc.recordFolderCompleted(runId, sourceFolder)
        } catch (e: Exception) {
            log.warn(
                "Failed to record folder-completed progress (jobRunId={}, folder='{}'): {}",
                runId, sourceFolder, e.message
            )
        }
    }

    private fun advanceToNextFolder() {
        val mapping = mappings[mappingIndex]
        mappingIndex++

        currentFolderName = mapping.source
        currentDestFolder = mapping.dest
        pending.clear()

        val store = sourceStore ?: return
        val folder = store.getFolder(mapping.source) as? IMAPFolder
        if (folder == null || !folder.exists() || (folder.type and Folder.HOLDS_MESSAGES) == 0) {
            log.warn("Source folder '{}' is missing or holds no messages; skipping", mapping.source)
            recordFolderOpenedSafely(mapping.source, mapping.dest, totalConsidered = 0L)
            return
        }
        folder.open(Folder.READ_ONLY)
        try {
            val messages = folder.messages
            if (messages.isEmpty()) {
                recordFolderOpenedSafely(mapping.source, mapping.dest, totalConsidered = 0L)
                return
            }

            var totalConsidered = 0
            val applyResume = isResumeFolder(mapping.source)
            // Batched FETCH of UID + Message-ID to cap memory on large folders.
            messages.toList().chunked(messageIdFetchBatchSize).forEach { batch ->
                val batchArray = batch.toTypedArray()
                folder.fetch(batchArray, FetchProfile().apply {
                    add(UIDFolder.FetchProfileItem.UID)
                    add("Message-ID")
                })

                val rows = batchArray.mapNotNull { msg ->
                    val uid = folder.getUID(msg)
                    if (uid <= 0) return@mapNotNull null
                    if (applyResume && uid <= resumeFromUid) return@mapNotNull null
                    val mid = try {
                        msg.getHeader("Message-ID")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    } catch (e: Exception) {
                        null
                    }
                    uid to mid
                }.sortedBy { it.first }

                totalConsidered += rows.size

                // Pre-filter against MirroredMessage to cut BODY[] fetches.
                // If a UID has no Message-ID header, the synthetic key is
                // deterministic — same scheme `ImapMirrorService` uses on the
                // real run — so it can be looked up here.
                for ((uid, mid) in rows) {
                    val lookupKey = mid ?: syntheticMessageId(mirrorConfigId, mapping.source, uid)
                    val alreadyMirrored = mirroredMessageRepository
                        .findByMirrorConfigIdAndMessageId(mirrorConfigId, lookupKey) != null
                    if (!alreadyMirrored) {
                        pending.add(uid)
                    }
                }
            }

            log.info(
                "MirrorJob folder '{}': {} UIDs considered, {} pending after pre-filter (mirrorConfigId={})",
                mapping.source, totalConsidered, pending.size, mirrorConfigId
            )
            recordFolderOpenedSafely(mapping.source, mapping.dest, totalConsidered.toLong())
        } finally {
            try { folder.close(false) } catch (_: Exception) {}
            // Resume marker only applies the *first* time we land on the resume folder.
            if (isResumeFolder(mapping.source)) {
                resumeApplied = true
            }
        }
    }

    private fun isResumeFolder(folderName: String): Boolean =
        !resumeApplied && resumeFolder != null && folderName == resumeFolder

    private fun syntheticMessageId(mirrorConfigId: Long, sourceFolder: String, sourceUid: Long): String =
        "synthetic:$mirrorConfigId:$sourceFolder:$sourceUid"

    fun close() {
        try { sourceStore?.close() } catch (_: Exception) {}
        sourceStore = null
        opened = false
    }

    /**
     * Pull `mirrorConfigId` directly from the job parameters so the reader
     * binds to the right config even if the surrounding `@JobScope` proxy
     * was created from a stale cached value (the failure mode we hit in
     * back-to-back integration tests against the same Spring context).
     * Also resets per-run state so a recycled reader doesn't carry buffers
     * across job executions.
     */
    override fun beforeStep(stepExecution: StepExecution) {
        val fromParams = stepExecution.jobExecution.jobParameters.getLong("mirrorConfigId")
        if (fromParams != null) {
            mirrorConfigId = fromParams
        }
        // JobRun id is stamped by the lifecycle listener in beforeJob; pull
        // it here so per-folder progress rows can be tied back to the run.
        val ctxJobRunId = stepExecution.jobExecution.executionContext.getLong(JOB_RUN_ID_KEY, -1L)
        jobRunId = if (ctxJobRunId > 0L) ctxJobRunId else null
        // Reset per-run state in case the bean instance is shared across
        // executions by the scope proxy.
        opened = false
        mappingIndex = 0
        currentFolderName = null
        currentDestFolder = null
        pending.clear()
        resumeFromUid = 0L
        resumeFolder = null
        resumeApplied = false
        try { sourceStore?.close() } catch (_: Exception) {}
        sourceStore = null
    }
}
