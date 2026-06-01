package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.MirroredMessage
import org.springframework.data.jpa.repository.JpaRepository

interface MirroredMessageRepository : JpaRepository<MirroredMessage, Long> {

    /**
     * Dry-run aggregate (issue #25): how many messages have already been
     * mirrored under a `(mirrorConfigId, sourceFolder)` pair.
     */
    fun countByMirrorConfigIdAndSourceFolder(mirrorConfigId: Long, sourceFolder: String): Long

    /**
     * Idempotency lookup (issue #23): has this `(mirrorConfigId, messageId)`
     * already been mirrored? If so, `ImapMirrorService.mirrorMessage` returns
     * `AlreadyMirrored` and does NOT touch the destination server.
     */
    fun findByMirrorConfigIdAndMessageId(mirrorConfigId: Long, messageId: String): MirroredMessage?

    /**
     * Progress lookup: enumerate what's already been copied for a folder pair
     * under this config. `MirrorJob` (issue #24) uses this to resume.
     */
    fun findByMirrorConfigIdAndSourceFolder(mirrorConfigId: Long, sourceFolder: String): List<MirroredMessage>

    fun countByMirrorConfigId(mirrorConfigId: Long): Long
}
