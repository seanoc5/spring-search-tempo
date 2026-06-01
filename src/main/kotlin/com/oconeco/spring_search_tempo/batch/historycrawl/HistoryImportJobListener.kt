package com.oconeco.spring_search_tempo.batch.historycrawl

import com.oconeco.spring_search_tempo.base.BrowserProfileService
import com.oconeco.spring_search_tempo.base.repos.BrowserProfileRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime


/**
 * Updates BrowserProfile sync state after history import — including
 * the [com.oconeco.spring_search_tempo.base.domain.BrowserProfile.lastHistoryVisitPrTime]
 * watermark used by the next incremental run.
 */
class HistoryImportJobListener(
    private val browserProfileService: BrowserProfileService,
    private val browserProfileRepository: BrowserProfileRepository,
    private val profileId: Long,
    private val processorHolder: () -> HistoryImportProcessor?
) : JobExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(HistoryImportJobListener::class.java)
    }

    override fun beforeJob(jobExecution: JobExecution) {
        log.info("Starting history import job for profile {}", profileId)
        browserProfileService.clearError(profileId)
    }

    @Transactional
    override fun afterJob(jobExecution: JobExecution) {
        when (jobExecution.status) {
            BatchStatus.COMPLETED -> {
                val stats = processorHolder()?.getStats()
                val writeCount = jobExecution.stepExecutions.sumOf { it.writeCount }.toInt()
                log.info(
                    "History import completed for profile {}. processed={} created={} updated={} errors={}",
                    profileId,
                    stats?.processed ?: writeCount,
                    stats?.created ?: writeCount,
                    stats?.updated ?: 0,
                    stats?.errors ?: 0
                )

                val profile = browserProfileRepository.findById(profileId)
                    .orElseThrow { NotFoundException("BrowserProfile not found: $profileId") }
                profile.lastHistorySyncAt = OffsetDateTime.now()
                profile.lastSyncHistoryCount = stats?.processed ?: writeCount
                stats?.maxVisitPrTime?.let { newWatermark ->
                    val current = profile.lastHistoryVisitPrTime ?: 0L
                    if (newWatermark > current) {
                        profile.lastHistoryVisitPrTime = newWatermark
                    }
                }
                browserProfileRepository.save(profile)
            }

            BatchStatus.FAILED -> {
                val errorMessage = jobExecution.allFailureExceptions
                    .firstOrNull()?.message ?: "Unknown error"
                log.error("History import failed for profile {}: {}", profileId, errorMessage)
                browserProfileService.recordError(profileId, errorMessage)
            }

            else -> {
                log.warn("History import ended with status {} for profile {}",
                    jobExecution.status, profileId)
            }
        }
    }

}
