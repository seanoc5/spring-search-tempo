package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.MirrorCheckpoint
import com.oconeco.spring_search_tempo.base.repos.MirrorCheckpointRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Persistence helper for [MirrorCheckpoint] (issue #24). One checkpoint row
 * per `MirrorConfig`; `MirrorJob` reads it on start to resume mid-folder
 * and clears it on successful completion.
 */
@Service
class MirrorCheckpointService(
    private val repository: MirrorCheckpointRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(MirrorCheckpointService::class.java)
    }

    fun find(mirrorConfigId: Long): MirrorCheckpoint? =
        repository.findByMirrorConfigId(mirrorConfigId)

    @Transactional
    fun upsert(mirrorConfigId: Long, currentFolder: String, lastSourceUidProcessed: Long): MirrorCheckpoint {
        val existing = repository.findByMirrorConfigId(mirrorConfigId)
        val checkpoint = existing ?: MirrorCheckpoint().apply {
            this.mirrorConfigId = mirrorConfigId
        }
        checkpoint.currentFolder = currentFolder
        checkpoint.lastSourceUidProcessed = lastSourceUidProcessed
        return repository.save(checkpoint)
    }

    @Transactional
    fun clear(mirrorConfigId: Long): Boolean {
        val deleted = repository.deleteByMirrorConfigId(mirrorConfigId)
        if (deleted > 0) {
            log.info("Cleared mirror checkpoint for mirrorConfigId={}", mirrorConfigId)
        }
        return deleted > 0
    }
}
