package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.MirrorError
import com.oconeco.spring_search_tempo.base.repos.MirrorErrorRepository
import com.oconeco.spring_search_tempo.base.service.mirror.ImapMirrorService
import com.oconeco.spring_search_tempo.base.service.mirror.MirrorResult
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemProcessor
import java.time.OffsetDateTime

/**
 * Calls `ImapMirrorService.mirrorMessage(...)` for each [MirrorTask] emitted
 * by [MirrorMessageReader].
 *
 * Outcomes:
 *  - `Copied` / `AlreadyMirrored` → return the task so the writer can
 *    advance the checkpoint.
 *  - `Failed` → persist a `MirrorError` row (with the service's
 *    retryable classification) and filter the task by returning `null`,
 *    keeping Spring Batch moving past the failure.
 *
 * Per-task progress lives on `JobRun.processedCount` / `currentStepName`
 * so the dashboard fragment (#26) can render counts without inspecting
 * Spring Batch internals.
 *
 * The throttle is intentionally enforced *inside* `ImapMirrorService` via
 * `MirrorRateLimiter` rather than here, so the reader can prepare the next
 * task while the previous one is parked on the bucket.
 */
class MirrorMessageProcessor(
    private val imapMirrorService: ImapMirrorService,
    private val mirrorErrorRepository: MirrorErrorRepository,
    private val jobRunService: JobRunService
) : ItemProcessor<MirrorTask, MirrorTask>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(MirrorMessageProcessor::class.java)
    }

    private var jobRunId: Long? = null

    override fun beforeStep(stepExecution: StepExecution) {
        val raw = stepExecution.jobExecution.executionContext
            .getLong(MirrorJobLifecycleListener.JOB_RUN_ID_KEY, -1L)
        jobRunId = raw.takeIf { it > 0 }
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
            is MirrorResult.AlreadyMirrored -> {
                updateProgress(item)
                item
            }
            is MirrorResult.Failed -> {
                log.warn(
                    "Mirror failed for mirrorConfigId={} src={}/{} dst={} retryable={}: {}",
                    item.mirrorConfigId, item.sourceFolder, item.sourceUid,
                    item.destFolder, result.retryable, result.reason
                )
                recordError(item, result)
                updateProgress(item)
                // Surface non-retryable failures by filtering; the audit
                // boundary lives in `ImapMirrorService` so re-runs will
                // re-attempt without producing duplicates.
                null
            }
        }
    }

    private fun updateProgress(item: MirrorTask) {
        val id = jobRunId ?: return
        try {
            jobRunService.updateProgress(
                jobRunId = id,
                processedIncrement = 1,
                stepName = "Mirroring ${item.sourceFolder} → ${item.destFolder}"
            )
        } catch (e: Exception) {
            log.debug("JobRun progress update failed for jobRunId={}: {}", id, e.message)
        }
    }

    private fun recordError(item: MirrorTask, failure: MirrorResult.Failed) {
        try {
            mirrorErrorRepository.save(
                MirrorError().apply {
                    this.mirrorConfigId = item.mirrorConfigId
                    this.jobRunId = this@MirrorMessageProcessor.jobRunId
                    this.sourceFolder = item.sourceFolder
                    this.sourceUid = item.sourceUid
                    this.destFolder = item.destFolder
                    this.reason = failure.reason
                    this.retryable = failure.retryable
                    this.occurredAt = OffsetDateTime.now()
                }
            )
        } catch (e: Exception) {
            log.error("Could not persist MirrorError for mirrorConfigId={} src={}/{}",
                item.mirrorConfigId, item.sourceFolder, item.sourceUid, e)
        }
    }
}
