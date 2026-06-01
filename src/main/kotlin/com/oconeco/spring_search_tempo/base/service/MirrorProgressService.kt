package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.MirrorConfigService
import com.oconeco.spring_search_tempo.base.domain.JobRun
import com.oconeco.spring_search_tempo.base.domain.MirrorError
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.model.FolderMapping
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorCheckpointRepository
import com.oconeco.spring_search_tempo.base.repos.MirrorErrorRepository
import com.oconeco.spring_search_tempo.base.repos.MirroredMessageRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Read-side aggregator for the mirror progress dashboard (#26).
 *
 * Everything here comes from the database — never IMAP — per the
 * dashboard's "no live IMAP polling" guardrail. The page header,
 * per-folder progress rows, and recent-error list are all sourced
 * from `JobRun`, `MirroredMessage`, `MirrorError`, and
 * `MirrorCheckpoint`.
 */
@Service
@Transactional(readOnly = true)
class MirrorProgressService(
    private val mirrorConfigService: MirrorConfigService,
    private val jobRunRepository: JobRunRepository,
    private val mirroredMessageRepository: MirroredMessageRepository,
    private val mirrorErrorRepository: MirrorErrorRepository,
    private val mirrorCheckpointRepository: MirrorCheckpointRepository,
    private val folderProgressService: MirrorFolderProgressService
) {

    fun getJobRunOrThrow(jobRunId: Long): JobRun =
        jobRunRepository.findById(jobRunId).orElseThrow { NotFoundException("JobRun $jobRunId not found") }

    fun loadProgress(mirrorConfigId: Long, jobRunId: Long): MirrorProgressView {
        val mirror = mirrorConfigService.get(mirrorConfigId)
        val jobRun = getJobRunOrThrow(jobRunId)

        require(jobRun.mirrorConfig?.id == mirrorConfigId) {
            "JobRun $jobRunId is not tied to MirrorConfig $mirrorConfigId"
        }

        val now = OffsetDateTime.now()
        val finish = jobRun.finishTime
        val start = jobRun.startTime
        val elapsedSeconds: Long? = start?.let {
            Duration.between(it, finish ?: now).seconds.coerceAtLeast(0L)
        }

        val checkpoint = mirrorCheckpointRepository.findByMirrorConfigId(mirrorConfigId)
        val folderProgressByFolder = folderProgressService.findByJobRun(jobRunId)
            .associateBy { it.sourceFolder }

        val folders = mirror.folderMappings.map { fm: FolderMapping ->
            val mirrored = mirroredMessageRepository
                .countByMirrorConfigIdAndSourceFolder(mirrorConfigId, fm.source)
            val failedThisRun = mirrorErrorRepository
                .countByMirrorConfigIdAndJobRunIdAndSourceFolder(mirrorConfigId, jobRunId, fm.source)
            val currentUid = if (checkpoint?.currentFolder == fm.source) checkpoint.lastSourceUidProcessed else null
            val fp = folderProgressByFolder[fm.source]
            FolderProgress(
                source = fm.source,
                dest = fm.dest,
                enabled = fm.enabled,
                mirroredCount = mirrored,
                failedThisRun = failedThisRun,
                currentUid = currentUid,
                totalConsidered = fp?.totalConsidered,
                folderComplete = fp?.completedAt != null,
                folderOpened = fp?.openedAt != null
            )
        }

        val foldersTotal = folders.count { it.enabled }
        val foldersComplete = folders.count { it.enabled && it.folderComplete }
        val foldersInFlight = folders.count { it.enabled && it.folderOpened && !it.folderComplete }

        val totalFailedThisRun = mirrorErrorRepository.countByMirrorConfigIdAndJobRunId(mirrorConfigId, jobRunId)
        val totalRetryableFailedThisRun = mirrorErrorRepository
            .countByMirrorConfigIdAndJobRunIdAndRetryable(mirrorConfigId, jobRunId, true)
        val totalMirroredAllTime = mirroredMessageRepository.countByMirrorConfigId(mirrorConfigId)
        val processed = jobRun.processedCount ?: 0L
        val expected = jobRun.expectedTotal?.takeIf { it > 0 } ?: maxOf(processed, totalMirroredAllTime + totalFailedThisRun)

        val recentErrors = mirrorErrorRepository
            .findByMirrorConfigIdAndJobRunIdOrderByOccurredAtAsc(
                mirrorConfigId, jobRunId,
                PageRequest.of(0, 100, Sort.by("occurredAt").descending())
            )
            .content

        return MirrorProgressView(
            mirrorConfigId = mirrorConfigId,
            mirrorName = mirror.name ?: "Mirror $mirrorConfigId",
            jobRun = jobRun,
            isRunning = jobRun.runStatus == RunStatus.RUNNING,
            elapsedSeconds = elapsedSeconds,
            folders = folders,
            foldersTotal = foldersTotal,
            foldersComplete = foldersComplete,
            foldersInFlight = foldersInFlight,
            processedCount = processed,
            expectedTotal = expected,
            totalMirroredAllTime = totalMirroredAllTime,
            totalFailedThisRun = totalFailedThisRun,
            retryableFailedThisRun = totalRetryableFailedThisRun,
            recentErrors = recentErrors
        )
    }
}

/**
 * Snapshot delivered to the dashboard template. All counts are
 * point-in-time reads from the DB.
 */
data class MirrorProgressView(
    val mirrorConfigId: Long,
    val mirrorName: String,
    val jobRun: JobRun,
    val isRunning: Boolean,
    val elapsedSeconds: Long?,
    val folders: List<FolderProgress>,
    /** Enabled folder mappings on the config — denominator for the status line. */
    val foldersTotal: Int,
    /** Folders whose reader pass has finished (completedAt is set). */
    val foldersComplete: Int,
    /** Folders that have been opened this run but not yet completed. */
    val foldersInFlight: Int,
    val processedCount: Long,
    val expectedTotal: Long,
    val totalMirroredAllTime: Long,
    val totalFailedThisRun: Long,
    val retryableFailedThisRun: Long,
    val recentErrors: List<MirrorError>
)

data class FolderProgress(
    val source: String,
    val dest: String,
    val enabled: Boolean,
    val mirroredCount: Long,
    val failedThisRun: Long,
    val currentUid: Long?,
    /**
     * UIDs the reader planned to walk for this folder this run, captured
     * at folder-open time. `null` means the reader hasn't opened the
     * folder yet (so we have no honest denominator to render).
     */
    val totalConsidered: Long?,
    val folderComplete: Boolean,
    val folderOpened: Boolean
)
