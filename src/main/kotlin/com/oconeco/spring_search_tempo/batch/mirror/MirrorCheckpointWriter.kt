package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.service.MirrorCheckpointService
import com.oconeco.spring_search_tempo.base.service.MirrorFolderCheckpointService
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter

/**
 * Persists a per-folder watermark + the legacy `MirrorCheckpoint` at the
 * end of each successfully-mirrored chunk. The "writer" doesn't actually
 * write the source/dest IMAP traffic — that already happened in
 * [MirrorMessageProcessor] via `ImapMirrorService`, which has its own
 * audit-row commit per message. What we write here is the *resume
 * marker*: the per-folder `lastSourceUid` (issue #39) so the next run
 * can pick up each folder mid-pass independently, plus the legacy
 * `MirrorCheckpoint` row used by the existing dashboard's "current
 * UID" cell.
 *
 * Checkpoint write strategy: end of each chunk, advancing each
 * `(mirrorConfigId, sourceFolder)` pair to the highest sourceUid seen.
 * If a chunk crosses a folder boundary (rare — the reader emits all of
 * folder A before any of folder B), each folder gets its own
 * `advance(...)` so a sibling's higher UID doesn't bleed into another
 * folder's watermark.
 *
 * Failure tolerance: if a chunk fails mid-way, `ImapMirrorService` has
 * already committed audit rows for the messages that completed, so the
 * next run's reader pre-filter skips them. The watermark advances on
 * the next successful chunk.
 */
class MirrorCheckpointWriter(
    private val checkpointService: MirrorCheckpointService,
    private val folderCheckpointService: MirrorFolderCheckpointService
) : ItemWriter<MirrorTask> {

    companion object {
        private val log = LoggerFactory.getLogger(MirrorCheckpointWriter::class.java)
    }

    override fun write(chunk: Chunk<out MirrorTask>) {
        if (chunk.isEmpty) return
        chunk.items
            .groupBy { it.mirrorConfigId to it.sourceFolder }
            .forEach { (key, items) ->
                val (mirrorConfigId, folder) = key
                val maxUid = items.maxOf { it.sourceUid }
                folderCheckpointService.advance(
                    mirrorConfigId = mirrorConfigId,
                    sourceFolder = folder,
                    lastSourceUid = maxUid
                )
                // Legacy single-row checkpoint kept for back-compat with
                // the existing dashboard's "current UID" cell.
                checkpointService.upsert(
                    mirrorConfigId = mirrorConfigId,
                    currentFolder = folder,
                    lastSourceUidProcessed = maxUid
                )
                log.debug(
                    "Mirror checkpoint advanced: mirrorConfigId={} folder='{}' lastUid={}",
                    mirrorConfigId, folder, maxUid
                )
            }
    }
}
