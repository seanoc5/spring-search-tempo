package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.service.MirrorCheckpointService
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter

/**
 * Persists a `MirrorCheckpoint` at the end of each successfully-mirrored
 * chunk. The "writer" doesn't actually write the source/dest IMAP traffic —
 * that already happened in [MirrorMessageProcessor] via `ImapMirrorService`,
 * which has its own audit-row commit per message. What we write here is the
 * *resume marker*: the `(currentFolder, lastSourceUidProcessed)` pair so the
 * next run can pick up mid-folder.
 *
 * Checkpoint write strategy: end of each chunk, advancing to the highest
 * sourceUid in the chunk. If a chunk crosses a folder boundary (rare — the
 * reader emits all of folder A before any of folder B), the checkpoint is
 * written per-folder so neither folder's resume marker overwrites the other.
 *
 * Failure tolerance: if a chunk fails mid-way, `ImapMirrorService` has
 * already committed audit rows for the messages that completed, so the
 * next run's reader pre-filter skips them. The checkpoint advances on the
 * next successful chunk.
 */
class MirrorCheckpointWriter(
    private val checkpointService: MirrorCheckpointService
) : ItemWriter<MirrorTask> {

    companion object {
        private val log = LoggerFactory.getLogger(MirrorCheckpointWriter::class.java)
    }

    override fun write(chunk: Chunk<out MirrorTask>) {
        if (chunk.isEmpty) return
        // The reader emits one folder at a time; in the rare case a chunk
        // straddles a folder boundary, persist the highest UID per folder.
        chunk.items
            .groupBy { it.mirrorConfigId to it.sourceFolder }
            .forEach { (key, items) ->
                val (mirrorConfigId, folder) = key
                val maxUid = items.maxOf { it.sourceUid }
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
