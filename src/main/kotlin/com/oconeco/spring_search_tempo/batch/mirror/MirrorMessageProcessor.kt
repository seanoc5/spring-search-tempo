package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.service.mirror.ImapMirrorService
import com.oconeco.spring_search_tempo.base.service.mirror.MirrorResult
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor

/**
 * Calls `ImapMirrorService.mirrorMessage(...)` for each [MirrorTask] emitted
 * by [MirrorMessageReader]. Returns the task unchanged on success or already-
 * mirrored outcomes so the writer can advance the checkpoint; returns `null`
 * on non-retryable failures so Spring Batch's skip/filter machinery keeps
 * the job moving.
 *
 * The throttle is intentionally enforced *inside* `ImapMirrorService` via
 * `MirrorRateLimiter` rather than here, so the reader can prepare the next
 * task while the previous one is parked on the bucket.
 */
class MirrorMessageProcessor(
    private val imapMirrorService: ImapMirrorService
) : ItemProcessor<MirrorTask, MirrorTask> {

    companion object {
        private val log = LoggerFactory.getLogger(MirrorMessageProcessor::class.java)
    }

    override fun process(item: MirrorTask): MirrorTask? {
        val result = imapMirrorService.mirrorMessage(
            mirrorConfigId = item.mirrorConfigId,
            sourceFolder = item.sourceFolder,
            sourceUid = item.sourceUid,
            destFolder = item.destFolder
        )
        return when (result) {
            is MirrorResult.Copied,
            is MirrorResult.AlreadyMirrored -> item
            is MirrorResult.Failed -> {
                log.warn(
                    "Mirror failed for mirrorConfigId={} src={}/{} dst={} retryable={}: {}",
                    item.mirrorConfigId, item.sourceFolder, item.sourceUid,
                    item.destFolder, result.retryable, result.reason
                )
                // Surface non-retryable failures by filtering; the audit
                // boundary lives in `ImapMirrorService` so re-runs will
                // re-attempt without producing duplicates.
                null
            }
        }
    }
}
