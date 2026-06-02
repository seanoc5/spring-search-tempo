package com.oconeco.spring_search_tempo.batch.mirror

import com.oconeco.spring_search_tempo.base.events.MirrorJobCompletedEvent
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Listens for `MirrorJobCompletedEvent` and launches an email quick-sync
 * against the destination account so the freshly mirrored messages become
 * `EmailMessage` / `ContentChunk` rows and pass through Stanford CoreNLP
 * (NLP is Pass 4 of `EmailQuickSyncJobBuilder` — no separate handoff needed).
 *
 * Toggle: `app.mirror.auto-index` (default `true`). Setting `false` skips
 * the autotrigger but leaves the existing manual `MirrorJobLauncher.launch`
 * path untouched.
 */
@Component
class MirrorIndexingTriggerListener(
    private val emailCrawlOrchestrator: EmailCrawlOrchestrator,
    @Value("\${app.mirror.auto-index:true}")
    private val autoIndexEnabled: Boolean
) {
    companion object {
        private val log = LoggerFactory.getLogger(MirrorIndexingTriggerListener::class.java)
    }

    /**
     * Runs AFTER_COMMIT (and FALLBACK so non-transactional publishers in
     * tests still see it) so the mirror job's COMPLETED state is durable
     * before the email quick-sync launches — we don't want a rollback
     * after the trigger has already started a downstream batch run.
     */
    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true
    )
    fun onMirrorCompleted(event: MirrorJobCompletedEvent) {
        if (!autoIndexEnabled) {
            log.info(
                "Mirror auto-index disabled (app.mirror.auto-index=false); skipping indexing for mirrorConfigId={} jobRunId={}",
                event.mirrorConfigId, event.jobRunId
            )
            return
        }

        val destAccountId = event.destAccountId
        if (destAccountId == null) {
            log.warn(
                "MirrorJobCompletedEvent has no destAccountId (mirrorConfigId={} jobRunId={}); skipping indexing",
                event.mirrorConfigId, event.jobRunId
            )
            return
        }

        if (event.messagesMirrored == 0L) {
            log.info(
                "Mirror produced 0 messages (mirrorConfigId={} jobRunId={}); skipping indexing",
                event.mirrorConfigId, event.jobRunId
            )
            return
        }

        log.info(
            "Mirror completed (mirrorConfigId={} jobRunId={} messagesMirrored={}); launching email quick-sync for destAccountId={}",
            event.mirrorConfigId, event.jobRunId, event.messagesMirrored, destAccountId
        )

        try {
            val execution = emailCrawlOrchestrator.runQuickSyncForAccount(accountId = destAccountId)
            log.info(
                "Email quick-sync launched after mirror: mirrorConfigId={} jobRunId={} destAccountId={} downstreamJobExecutionId={}",
                event.mirrorConfigId, event.jobRunId, destAccountId, execution.id
            )
        } catch (e: Exception) {
            // Indexing is supplementary — never propagate so the mirror job
            // itself stays COMPLETED.
            log.error(
                "Failed to launch email quick-sync after mirror (mirrorConfigId={} destAccountId={}): {}",
                event.mirrorConfigId, destAccountId, e.message, e
            )
        }
    }
}
