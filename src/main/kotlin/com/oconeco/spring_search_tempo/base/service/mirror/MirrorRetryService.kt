package com.oconeco.spring_search_tempo.base.service.mirror

import com.oconeco.spring_search_tempo.base.repos.MirrorErrorRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Replays `MirrorError(retryable=true)` rows through
 * [ImapMirrorService.mirrorMessage]. Each successful retry deletes its
 * `MirrorError` row; a still-failing retry rewrites `reason`,
 * `retryable`, and `occurredAt` so the dashboard reflects the latest
 * attempt.
 *
 * Non-retryable errors are left untouched: the retry endpoint is a
 * "transient-failure replay" tool, not a force-overwrite. A permanent
 * failure needs operator intervention (or a config change) before any
 * automatic re-run would be safe.
 *
 * Synchronous on purpose: the caller is an authenticated operator
 * pressing the retry button and waiting for the count. Async/queueing
 * is a future refinement if the retryable set grows large.
 */
@Service
class MirrorRetryService(
    private val mirrorErrorRepository: MirrorErrorRepository,
    private val imapMirrorService: ImapMirrorService
) {

    companion object {
        private val log = LoggerFactory.getLogger(MirrorRetryService::class.java)
    }

    @Transactional
    fun retryFailed(mirrorConfigId: Long): RetrySummary {
        val candidates = mirrorErrorRepository
            .findByMirrorConfigIdAndRetryable(mirrorConfigId, true)
        if (candidates.isEmpty()) {
            return RetrySummary(attempted = 0, succeeded = 0, stillFailing = 0)
        }

        var succeeded = 0
        var stillFailing = 0

        for (err in candidates) {
            val sourceFolder = err.sourceFolder ?: continue
            val sourceUid = err.sourceUid ?: continue
            val destFolder = err.destFolder ?: sourceFolder

            val result = imapMirrorService.mirrorMessage(
                mirrorConfigId = mirrorConfigId,
                sourceFolder = sourceFolder,
                sourceUid = sourceUid,
                destFolder = destFolder
            )
            when (result) {
                is MirrorResult.Copied,
                is MirrorResult.AlreadyMirrored -> {
                    mirrorErrorRepository.delete(err)
                    succeeded++
                }
                is MirrorResult.Failed -> {
                    err.reason = result.reason
                    err.retryable = result.retryable
                    err.occurredAt = OffsetDateTime.now()
                    mirrorErrorRepository.save(err)
                    stillFailing++
                }
            }
        }

        log.info(
            "Retry-failed for mirrorConfigId={}: attempted={} succeeded={} stillFailing={}",
            mirrorConfigId, candidates.size, succeeded, stillFailing
        )
        return RetrySummary(
            attempted = candidates.size,
            succeeded = succeeded,
            stillFailing = stillFailing
        )
    }
}

data class RetrySummary(
    val attempted: Int,
    val succeeded: Int,
    val stillFailing: Int
)
