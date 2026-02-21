package com.oconeco.spring_search_tempo.batch.bookmarkcrawl

import com.oconeco.spring_search_tempo.base.BrowserProfileService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener


/**
 * Job execution listener that updates browser profile sync state.
 */
class BookmarkImportJobListener(
    private val browserProfileService: BrowserProfileService,
    private val profileId: Long
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(BookmarkImportJobListener::class.java)
    }

    override fun beforeJob(jobExecution: JobExecution) {
        log.info("Starting bookmark import job for profile {}", profileId)
        browserProfileService.clearError(profileId)
    }

    override fun afterJob(jobExecution: JobExecution) {
        when (jobExecution.status) {
            BatchStatus.COMPLETED -> {
                val writeCount = jobExecution.stepExecutions
                    .sumOf { it.writeCount }
                    .toInt()

                log.info("Bookmark import completed for profile {}. Imported {} bookmarks",
                    profileId, writeCount)

                browserProfileService.updateSyncState(profileId, writeCount)
            }

            BatchStatus.FAILED -> {
                val errorMessage = jobExecution.allFailureExceptions
                    .firstOrNull()?.message ?: "Unknown error"

                log.error("Bookmark import failed for profile {}: {}",
                    profileId, errorMessage)

                browserProfileService.recordError(profileId, errorMessage)
            }

            else -> {
                log.warn("Bookmark import ended with status {} for profile {}",
                    jobExecution.status, profileId)
            }
        }
    }

}
