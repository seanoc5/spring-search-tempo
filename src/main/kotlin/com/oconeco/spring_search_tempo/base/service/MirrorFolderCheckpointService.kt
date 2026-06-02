package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.MirrorFolderCheckpoint
import com.oconeco.spring_search_tempo.base.repos.MirrorFolderCheckpointRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Persistence helper for [MirrorFolderCheckpoint] (issue #39). One row per
 * `(mirrorConfigId, sourceFolder)`; the reader looks them up at folder
 * open time to derive each folder's resume UID, and the writer upserts
 * the maximum UID in every chunk so a mid-folder interruption picks up
 * where it left off.
 */
@Service
class MirrorFolderCheckpointService(
    private val repository: MirrorFolderCheckpointRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(MirrorFolderCheckpointService::class.java)
    }

    @Transactional(readOnly = true)
    fun findAll(mirrorConfigId: Long): List<MirrorFolderCheckpoint> =
        repository.findByMirrorConfigId(mirrorConfigId)

    @Transactional(readOnly = true)
    fun find(mirrorConfigId: Long, sourceFolder: String): MirrorFolderCheckpoint? =
        repository.findByMirrorConfigIdAndSourceFolder(mirrorConfigId, sourceFolder)

    /**
     * Upsert the watermark for one folder. Only advances `lastSourceUid`
     * forward — never rewinds — so an out-of-order write from a stale
     * chunk doesn't reset progress.
     */
    @Transactional
    fun advance(mirrorConfigId: Long, sourceFolder: String, lastSourceUid: Long): MirrorFolderCheckpoint {
        val existing = repository.findByMirrorConfigIdAndSourceFolder(mirrorConfigId, sourceFolder)
        val row = existing ?: MirrorFolderCheckpoint().apply {
            this.mirrorConfigId = mirrorConfigId
            this.sourceFolder = sourceFolder
        }
        if (lastSourceUid > row.lastSourceUid) {
            row.lastSourceUid = lastSourceUid
        }
        return repository.save(row)
    }

    @Transactional
    fun clear(mirrorConfigId: Long): Int {
        val deleted = repository.deleteByMirrorConfigId(mirrorConfigId)
        if (deleted > 0) {
            log.info("Cleared {} per-folder checkpoint rows for mirrorConfigId={}", deleted, mirrorConfigId)
        }
        return deleted
    }
}
