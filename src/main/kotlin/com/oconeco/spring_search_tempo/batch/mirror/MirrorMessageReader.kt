package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.domain.MirrorError
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.repos.MirrorErrorRepository
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.oconeco.spring_search_tempo.base.service.MirrorCheckpointService
import com.oconeco.spring_search_tempo.base.service.MirrorFolderCheckpointService
import com.oconeco.spring_search_tempo.base.service.MirrorFolderProgressService
import com.oconeco.spring_search_tempo.batch.mirror.MirrorJobLifecycleListener.Companion.JOB_RUN_ID_KEY
import com.sun.mail.imap.IMAPFolder
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemReader
import java.time.OffsetDateTime
import java.util.ArrayDeque

/**
 * Spring Batch `ItemReader` that walks every enabled folder mapping in a
 * `MirrorConfig`, enumerating source UIDs in ascending order and emitting
 * one `MirrorTask` per UID that still needs copying.
 *
 * Per-folder error isolation (issue #39): each folder's open / fetch
 * pass is wrapped in its own try/catch. A failure on folder B logs a
 * folder-scope [MirrorError] row, stamps the folder as FAILED on the
 * progress dashboard, and advances to the next folder rather than
 * aborting the step. The step's exit status is decided in [afterStep]:
 * COMPLETED if at least one folder succeeded, FAILED only when every
 * folder failed.
 *
 * Per-folder resume markers (issue #39): each `(mirrorConfigId,
 * sourceFolder)` pair has its own [MirrorFolderCheckpoint] watermark,
 * so a retry resumes every folder from its own last good UID
 * independently. The legacy single-row [MirrorCheckpoint] is still
 * consulted as a fallback for back-compat.
 */
class MirrorMessageReader(
    mirrorConfigId: Long,
    private val mirrorConfigService: MirrorConfigService,
    private val emailAccountService: EmailAccountService,
    private val imapConnectionService: ImapConnectionService,
    private val mirroredMessageRepository: MirroredMessageRepository,
    private val checkpointService: MirrorCheckpointService,
    private val folderCheckpointService: MirrorFolderCheckpointService,
    private val folderProgressService: MirrorFolderProgressService? = null,
    private val mirrorErrorRepository: MirrorErrorRepository? = null,
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
    private var perFolderResumeUids: Map<String, Long> = emptyMap()
    private var legacyResumeFromUid: Long = 0L
    private var legacyResumeFolder: String? = null
    private var legacyResumeApplied: Boolean = false
    private var opened: Boolean = false

    // Aggregates for afterStep — drive the COMPLETED-if-any-succeeded
    // decision and the lifecycle summary log.
    private var foldersAttempted: Int = 0
    private var foldersSucceeded: Int = 0
    private var foldersFailed: Int = 0
    private val foldersFailedNames: MutableList<String> = mutableListOf()

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

        perFolderResumeUids = folderCheckpointService.findAll(mirrorConfigId)
            .mapNotNull { fc ->
                val folder = fc.sourceFolder ?: return@mapNotNull null
                folder to fc.lastSourceUid
            }
            .toMap()

        checkpointService.find(mirrorConfigId)?.let { cp ->
            legacyResumeFolder = cp.currentFolder
            legacyResumeFromUid = cp.lastSourceUidProcessed ?: 0L
        }

        if (perFolderResumeUids.isNotEmpty() || legacyResumeFolder != null) {
            log.info(
                "Resuming MirrorJob: mirrorConfigId={} perFolderWatermarks={} legacyResumeFolder={} legacyResumeFromUid={}",
                mirrorConfigId, perFolderResumeUids, legacyResumeFolder, legacyResumeFromUid
            )
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

    private fun recordFolderFailedSafely(sourceFolder: String, destFolder: String) {
        val svc = folderProgressService ?: return
        val runId = jobRunId ?: return
        try {
            svc.recordFolderFailed(
                mirrorConfigId = mirrorConfigId,
                jobRunId = runId,
                sourceFolder = sourceFolder,
                destFolder = destFolder
            )
        } catch (e: Exception) {
            log.warn(
                "Failed to record folder-failed progress (jobRunId={}, folder='{}'): {}",
                runId, sourceFolder, e.message
            )
        }
    }

    /**
     * Persist a folder-scope `MirrorError` row so the dashboard can
     * surface that the whole folder failed (as opposed to a single
     * message). Tagged `retryable=true` because a connection / quota /
     * auth blip is exactly the kind of transient failure the operator
     * may want to manually re-run after fixing the underlying cause.
     */
    private fun recordFolderError(mapping: FolderMapping, ex: Throwable) {
        val repo = mirrorErrorRepository ?: return
        try {
            repo.save(
                MirrorError().apply {
                    this.mirrorConfigId = this@MirrorMessageReader.mirrorConfigId
                    this.jobRunId = this@MirrorMessageReader.jobRunId
                    this.sourceFolder = mapping.source
                    this.sourceUid = 0L
                    this.messageId = null
                    this.destFolder = mapping.dest
                    this.reason = "Folder enumeration failed: ${ex.message ?: ex.javaClass.simpleName}"
                    this.retryable = true
                    this.errorScope = "FOLDER"
                    this.occurredAt = OffsetDateTime.now()
                }
            )
        } catch (e: Exception) {
            log.error(
                "Could not persist folder-scope MirrorError for mirrorConfigId={} folder='{}'",
                mirrorConfigId, mapping.source, e
            )
        }
    }

    private fun advanceToNextFolder() {
        val mapping = mappings[mappingIndex]
        mappingIndex++
        foldersAttempted++

        currentFolderName = mapping.source
        currentDestFolder = mapping.dest
        pending.clear()

        val store = sourceStore
        if (store == null) {
            // No source store means ensureOpen short-circuited (no
            // enabled mappings) — nothing to enumerate.
            foldersFailed++
            foldersFailedNames += mapping.source
            return
        }

        try {
            enumerateFolder(store, mapping)
            foldersSucceeded++
        } catch (e: Exception) {
            // Per-folder isolation (issue #39): a connection blip, quota
            // error, or auth failure on this folder must NOT abort the
            // step. Log the failure as a folder-scope error, mark the
            // folder failed on the dashboard, and let `read()` advance
            // to the next mapping.
            log.warn(
                "MirrorJob folder enumeration failed: mirrorConfigId={} folder='{}' — continuing to next folder: {}",
                mirrorConfigId, mapping.source, e.message, e
            )
            recordFolderError(mapping, e)
            recordFolderFailedSafely(mapping.source, mapping.dest)
            // Drop any partially-built pending buffer; the reader will
            // resume this folder from its watermark on a future retry.
            pending.clear()
            foldersFailed++
            foldersFailedNames += mapping.source
        }
    }

    /**
     * Open one folder, list its UIDs, apply the resume watermark + the
     * MirroredMessage pre-filter, and stage the survivors in [pending].
     * Throws on IMAP-level failure so [advanceToNextFolder] can decide
     * how to recover.
     */
    private fun enumerateFolder(store: Store, mapping: FolderMapping) {
        val folder = store.getFolder(mapping.source) as? IMAPFolder
        if (folder == null || !folder.exists() || (folder.type and Folder.HOLDS_MESSAGES) == 0) {
            log.warn("Source folder '{}' is missing or holds no messages; skipping", mapping.source)
            recordFolderOpenedSafely(mapping.source, mapping.dest, totalConsidered = 0L)
            return
        }
        val resumeUid = resumeUidFor(mapping.source)
        folder.open(Folder.READ_ONLY)
        try {
            val messages = folder.messages
            if (messages.isEmpty()) {
                recordFolderOpenedSafely(mapping.source, mapping.dest, totalConsidered = 0L)
                return
            }

            var totalConsidered = 0
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
                    if (uid <= resumeUid) return@mapNotNull null
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
                "MirrorJob folder '{}': {} UIDs considered, {} pending after pre-filter (mirrorConfigId={}, resumeUid={})",
                mapping.source, totalConsidered, pending.size, mirrorConfigId, resumeUid
            )
            recordFolderOpenedSafely(mapping.source, mapping.dest, totalConsidered.toLong())
        } finally {
            try { folder.close(false) } catch (_: Exception) {}
            // Legacy resume marker only applies once.
            if (mapping.source == legacyResumeFolder) {
                legacyResumeApplied = true
            }
        }
    }

    /**
     * Per-folder watermark for [folderName]. Prefers the new sibling
     * table; falls back to the legacy `MirrorCheckpoint` row the first
     * time we land on its `currentFolder`.
     */
    private fun resumeUidFor(folderName: String): Long {
        perFolderResumeUids[folderName]?.let { return it }
        if (!legacyResumeApplied && legacyResumeFolder == folderName) {
            return legacyResumeFromUid
        }
        return 0L
    }

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
        perFolderResumeUids = emptyMap()
        legacyResumeFromUid = 0L
        legacyResumeFolder = null
        legacyResumeApplied = false
        foldersAttempted = 0
        foldersSucceeded = 0
        foldersFailed = 0
        foldersFailedNames.clear()
        try { sourceStore?.close() } catch (_: Exception) {}
        sourceStore = null
    }

    /**
     * Decide the step's exit status from the per-folder tally (issue #39):
     * — at least one folder succeeded → COMPLETED (lets Spring Batch
     *   move on, the dashboard render the partial success, and the
     *   lifecycle listener clear the global checkpoint).
     * — every folder failed → FAILED (the run accomplished nothing;
     *   per-folder watermarks stay put so a retry replays everything).
     * — no folders attempted (no enabled mappings) → COMPLETED (the
     *   empty case isn't a failure).
     *
     * Also stamps a one-line summary in the lifecycle log per acceptance
     * criterion 4.
     */
    override fun afterStep(stepExecution: StepExecution): ExitStatus? {
        log.info(
            "MirrorJob step summary: mirrorConfigId={} foldersAttempted={} foldersSucceeded={} foldersFailed={}{}",
            mirrorConfigId, foldersAttempted, foldersSucceeded, foldersFailed,
            if (foldersFailedNames.isNotEmpty()) " failed=$foldersFailedNames" else ""
        )
        return when {
            foldersAttempted == 0 -> null
            foldersSucceeded == 0 -> ExitStatus.FAILED.addExitDescription(
                "All $foldersAttempted folder(s) failed: $foldersFailedNames"
            )
            else -> null
        }
    }
}
