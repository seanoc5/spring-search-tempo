package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.MirrorFolderProgress
import com.oconeco.spring_search_tempo.base.repos.MirrorFolderProgressRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Persistence helper for [MirrorFolderProgress] (issue #33). The reader
 * calls [recordFolderOpened] when it begins enumerating a source folder
 * and [recordFolderCompleted] when it advances past it; the progress
 * dashboard reads via [findByJobRun] to render denominators and the
 * folders-complete count.
 */
@Service
class MirrorFolderProgressService(
    private val repository: MirrorFolderProgressRepository
) {

    @Transactional(readOnly = true)
    fun findByJobRun(jobRunId: Long): List<MirrorFolderProgress> =
        repository.findByJobRunId(jobRunId)

    /**
     * Upsert the open-time snapshot for a folder. Idempotent on
     * `(jobRunId, sourceFolder)` so a re-opened folder (e.g., resumed
     * step) refreshes `totalConsidered` rather than inserting a
     * duplicate row.
     */
    @Transactional
    fun recordFolderOpened(
        mirrorConfigId: Long,
        jobRunId: Long,
        sourceFolder: String,
        destFolder: String?,
        totalConsidered: Long
    ): MirrorFolderProgress {
        val row = repository.findByJobRunIdAndSourceFolder(jobRunId, sourceFolder)
            ?: MirrorFolderProgress().apply {
                this.mirrorConfigId = mirrorConfigId
                this.jobRunId = jobRunId
                this.sourceFolder = sourceFolder
            }
        row.destFolder = destFolder
        row.totalConsidered = totalConsidered
        if (row.openedAt == null) {
            row.openedAt = OffsetDateTime.now()
        }
        row.completedAt = null
        row.status = "IN_FLIGHT"
        return repository.save(row)
    }

    @Transactional
    fun recordFolderCompleted(jobRunId: Long, sourceFolder: String) {
        val row = repository.findByJobRunIdAndSourceFolder(jobRunId, sourceFolder) ?: return
        row.completedAt = OffsetDateTime.now()
        // Don't overwrite a FAILED status — a folder that completed its
        // partial pass after a recoverable error still ends FAILED.
        if (row.status == "IN_FLIGHT") {
            row.status = "COMPLETED"
        }
        repository.save(row)
    }

    /**
     * Stamp a folder as failed (issue #39): the reader couldn't open or
     * enumerate the folder, so it logged a folder-scope `MirrorError`
     * and moved on. We still stamp `completedAt` so the "folders
     * complete" count includes failed folders — the run is finished
     * with that folder either way.
     */
    @Transactional
    fun recordFolderFailed(
        mirrorConfigId: Long,
        jobRunId: Long,
        sourceFolder: String,
        destFolder: String?
    ): MirrorFolderProgress {
        val row = repository.findByJobRunIdAndSourceFolder(jobRunId, sourceFolder)
            ?: MirrorFolderProgress().apply {
                this.mirrorConfigId = mirrorConfigId
                this.jobRunId = jobRunId
                this.sourceFolder = sourceFolder
                this.destFolder = destFolder
                this.openedAt = OffsetDateTime.now()
            }
        if (row.destFolder == null) row.destFolder = destFolder
        row.status = "FAILED"
        row.completedAt = OffsetDateTime.now()
        return repository.save(row)
    }
}
