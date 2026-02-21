package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.service.CrawlDataCleanupService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.stereotype.Component

/**
 * Job execution listener that performs pre-crawl cleanup when requested.
 * Checks the "deleteExistingData" job parameter and deletes all existing
 * data for the crawl config before the crawl starts.
 *
 * This listener should be registered BEFORE JobRunTrackingListener so that
 * cleanup happens before the new job run is recorded.
 */
@Component
class CrawlCleanupListener(
    private val cleanupService: CrawlDataCleanupService
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(CrawlCleanupListener::class.java)
        const val DELETE_EXISTING_DATA_KEY = "deleteExistingData"
    }

    override fun beforeJob(jobExecution: JobExecution) {
        val deleteExistingData = jobExecution.jobParameters.getString(DELETE_EXISTING_DATA_KEY)?.toBoolean() ?: false
        val crawlConfigIdParam = jobExecution.jobParameters.getString(JobRunTrackingListener.CRAWL_CONFIG_ID_KEY)

        if (!deleteExistingData) {
            log.debug("deleteExistingData is false, skipping cleanup")
            return
        }

        if (crawlConfigIdParam == null) {
            log.warn("deleteExistingData is true but no crawlConfigId found, skipping cleanup")
            return
        }

        try {
            val crawlConfigId = crawlConfigIdParam.toLong()
            log.info("Performing pre-crawl cleanup for crawl config {} (deleteExistingData=true)", crawlConfigId)

            val summary = cleanupService.deleteAllDataForCrawlConfig(crawlConfigId)

            if (summary.isEmpty) {
                log.info("No existing data to delete for crawl config {}", crawlConfigId)
            } else {
                log.info("Pre-crawl cleanup complete: deleted {} chunks, {} files, {} folders",
                    summary.chunksDeleted, summary.filesDeleted, summary.foldersDeleted)
            }

            // Store cleanup summary in execution context for potential use by other components
            jobExecution.executionContext.putInt("cleanupChunksDeleted", summary.chunksDeleted)
            jobExecution.executionContext.putInt("cleanupFilesDeleted", summary.filesDeleted)
            jobExecution.executionContext.putInt("cleanupFoldersDeleted", summary.foldersDeleted)

        } catch (e: Exception) {
            log.error("Pre-crawl cleanup failed for crawl config {}: {}", crawlConfigIdParam, e.message, e)
            // Don't fail the job - log the error and continue with the crawl
            // The crawl may still work, just with old data not deleted
        }
    }

    override fun afterJob(jobExecution: JobExecution) {
        // No cleanup needed after job completion
    }
}
