package com.oconeco.spring_search_tempo.batch.historycrawl

import com.oconeco.spring_search_tempo.base.BrowserProfileService
import com.oconeco.spring_search_tempo.base.config.BrowserHistoryConfiguration
import com.oconeco.spring_search_tempo.base.model.BrowserProfileDTO
import com.oconeco.spring_search_tempo.base.repos.BrowserBookmarkRepository
import com.oconeco.spring_search_tempo.base.repos.BrowserProfileRepository
import com.oconeco.spring_search_tempo.base.service.BrowserBookmarkMapper
import com.oconeco.spring_search_tempo.base.service.FirefoxPlacesService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Path


/**
 * Builds Spring Batch jobs for importing Firefox history entries.
 *
 * One job per browser profile, mirroring the bookmark-import flow.
 */
@Component
class HistoryImportJobBuilder(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val firefoxPlacesService: FirefoxPlacesService,
    private val browserProfileService: BrowserProfileService,
    private val browserBookmarkRepository: BrowserBookmarkRepository,
    private val browserProfileRepository: BrowserProfileRepository,
    private val browserBookmarkMapper: BrowserBookmarkMapper,
    private val browserHistoryConfiguration: BrowserHistoryConfiguration
) {

    companion object {
        private val log = LoggerFactory.getLogger(HistoryImportJobBuilder::class.java)
        const val CHUNK_SIZE = 200
    }

    fun buildJob(profile: BrowserProfileDTO): Job {
        log.info("Building history import job for profile: {} (id={})",
            profile.profileName, profile.id)

        val profileId = profile.id ?: throw IllegalArgumentException("Profile ID is required")
        val placesDbPath = profile.placesDbPath
            ?: throw IllegalArgumentException("Places database path is required")

        // Capture the processor so the listener can read its end-of-job stats
        // (counts + max-visit watermark). The processor is recreated per step
        // execution, so we store the most recent instance through a holder.
        var processorRef: HistoryImportProcessor? = null
        val processorHolder = { processorRef }

        val step = buildImportStep(profileId, Path.of(placesDbPath), profile.lastHistoryVisitPrTime) {
            processorRef = it
        }

        return JobBuilder("historyImportJob_${profileId}", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(
                HistoryImportJobListener(
                    browserProfileService = browserProfileService,
                    browserProfileRepository = browserProfileRepository,
                    profileId = profileId,
                    processorHolder = processorHolder
                )
            )
            .start(step)
            .build()
    }

    private fun buildImportStep(
        profileId: Long,
        placesDbPath: Path,
        sinceVisitPrTime: Long?,
        processorSink: (HistoryImportProcessor) -> Unit
    ): Step {
        val retentionDays = browserHistoryConfiguration.retentionDays
        log.info(
            "Building history import step for profile {} from {} (retentionDays={}, sincePrTime={})",
            profileId, placesDbPath, retentionDays, sinceVisitPrTime
        )

        val processor = HistoryImportProcessor(
            browserBookmarkRepository = browserBookmarkRepository,
            browserProfileId = profileId
        )
        processorSink(processor)

        return StepBuilder("historyImport_${profileId}", jobRepository)
            .chunk<FirefoxPlacesService.FirefoxHistoryData, HistoryProcessorResult>(
                CHUNK_SIZE, transactionManager
            )
            .reader(HistoryImportReader(placesDbPath, firefoxPlacesService, sinceVisitPrTime, retentionDays))
            .processor(processor)
            .writer(
                HistoryImportWriter(
                    browserBookmarkRepository = browserBookmarkRepository,
                    browserProfileRepository = browserProfileRepository,
                    browserBookmarkMapper = browserBookmarkMapper,
                    browserProfileId = profileId
                )
            )
            .build()
    }

}
