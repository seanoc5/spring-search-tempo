package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.service.CrawlCheckpointService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.stereotype.Component

/**
 * Resumes a crawl from its persisted checkpoint (issue #8) and clears the
 * checkpoint on successful completion.
 *
 * Lifecycle:
 *  - **beforeJob**: if a [com.oconeco.spring_search_tempo.base.domain.CrawlCheckpoint]
 *    exists for this crawl config, copy its `lastProcessedUri` into the job
 *    execution context under [CombinedCrawlReader.RESUME_FROM_URI_KEY]. The reader
 *    reads this in its own `beforeStep` and skips directories already processed.
 *    `forceFullRecrawl=true` bypasses the resume and clears the stale checkpoint.
 *  - **afterJob**: on `COMPLETED`, delete the checkpoint so the next run starts
 *    fresh. On any other status (FAILED, STOPPED) the checkpoint is preserved
 *    so the next run can resume.
 */
@Component
class CrawlCheckpointListener(
    private val checkpointService: CrawlCheckpointService
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(CrawlCheckpointListener::class.java)
        const val FORCE_FULL_RECRAWL_KEY = "forceFullRecrawl"
    }

    override fun beforeJob(jobExecution: JobExecution) {
        val crawlConfigId = jobExecution.jobParameters
            .getString(JobRunTrackingListener.CRAWL_CONFIG_ID_KEY)
            ?.toLongOrNull()
            ?: return

        val forceFullRecrawl = jobExecution.jobParameters
            .getString(FORCE_FULL_RECRAWL_KEY)
            ?.toBoolean()
            ?: false

        if (forceFullRecrawl) {
            // A user-requested full recrawl invalidates any prior partial-run state.
            val cleared = checkpointService.clear(crawlConfigId)
            if (cleared) {
                log.info("forceFullRecrawl=true: cleared existing checkpoint for crawlConfigId={}", crawlConfigId)
            }
            return
        }

        val checkpoint = checkpointService.find(crawlConfigId) ?: return
        val resumeUri = checkpoint.lastProcessedUri ?: return
        jobExecution.executionContext.putString(CombinedCrawlReader.RESUME_FROM_URI_KEY, resumeUri)
        log.info(
            "Resuming crawl for crawlConfigId={} from checkpoint URI: {} (checkpointUpdatedAt={})",
            crawlConfigId, resumeUri, checkpoint.updatedAt
        )
    }

    override fun afterJob(jobExecution: JobExecution) {
        val crawlConfigId = jobExecution.jobParameters
            .getString(JobRunTrackingListener.CRAWL_CONFIG_ID_KEY)
            ?.toLongOrNull()
            ?: return

        if (jobExecution.status == BatchStatus.COMPLETED) {
            val cleared = checkpointService.clear(crawlConfigId)
            if (cleared) {
                log.info("Crawl completed successfully — cleared checkpoint for crawlConfigId={}", crawlConfigId)
            }
        } else {
            log.info(
                "Crawl ended with status={} — preserving checkpoint for crawlConfigId={} so next run can resume",
                jobExecution.status, crawlConfigId
            )
        }
    }
}
