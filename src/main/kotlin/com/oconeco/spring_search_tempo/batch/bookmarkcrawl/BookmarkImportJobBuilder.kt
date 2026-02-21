package com.oconeco.spring_search_tempo.batch.bookmarkcrawl

import com.oconeco.spring_search_tempo.base.BookmarkTagService
import com.oconeco.spring_search_tempo.base.BrowserBookmarkService
import com.oconeco.spring_search_tempo.base.BrowserProfileService
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
 * Builder for creating bookmark import batch jobs dynamically.
 *
 * Creates a job for each browser profile to import bookmarks.
 */
@Component
class BookmarkImportJobBuilder(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val firefoxPlacesService: FirefoxPlacesService,
    private val browserBookmarkService: BrowserBookmarkService,
    private val browserProfileService: BrowserProfileService,
    private val bookmarkTagService: BookmarkTagService,
    private val browserBookmarkRepository: BrowserBookmarkRepository,
    private val browserProfileRepository: BrowserProfileRepository,
    private val browserBookmarkMapper: BrowserBookmarkMapper
) {

    companion object {
        private val log = LoggerFactory.getLogger(BookmarkImportJobBuilder::class.java)
        const val CHUNK_SIZE = 100
    }

    /**
     * Build a bookmark import job for a browser profile.
     *
     * @param profile The browser profile to import from
     * @return A configured Spring Batch Job
     */
    fun buildJob(profile: BrowserProfileDTO): Job {
        log.info("Building bookmark import job for profile: {} (id={})",
            profile.profileName, profile.id)

        val profileId = profile.id ?: throw IllegalArgumentException("Profile ID is required")
        val placesDbPath = profile.placesDbPath
            ?: throw IllegalArgumentException("Places database path is required")

        return JobBuilder("bookmarkImportJob_${profileId}", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(BookmarkImportJobListener(browserProfileService, profileId))
            .start(buildImportStep(profileId, Path.of(placesDbPath)))
            .build()
    }

    private fun buildImportStep(profileId: Long, placesDbPath: Path): Step {
        log.info("Building bookmark import step for profile {} from {}",
            profileId, placesDbPath)

        return StepBuilder("bookmarkImport_${profileId}", jobRepository)
            .chunk<com.oconeco.spring_search_tempo.base.service.FirefoxPlacesService.FirefoxBookmarkData,
                    BookmarkProcessorResult>(CHUNK_SIZE, transactionManager)
            .reader(createReader(placesDbPath))
            .processor(createProcessor(profileId))
            .writer(createWriter(profileId))
            .build()
    }

    private fun createReader(placesDbPath: Path): BookmarkImportReader {
        return BookmarkImportReader(placesDbPath, firefoxPlacesService)
    }

    private fun createProcessor(profileId: Long): BookmarkImportProcessor {
        return BookmarkImportProcessor(
            browserBookmarkService = browserBookmarkService,
            bookmarkTagService = bookmarkTagService,
            browserProfileId = profileId
        )
    }

    private fun createWriter(profileId: Long): BookmarkImportWriter {
        return BookmarkImportWriter(
            browserBookmarkRepository = browserBookmarkRepository,
            browserProfileRepository = browserProfileRepository,
            browserBookmarkMapper = browserBookmarkMapper,
            bookmarkTagService = bookmarkTagService,
            browserProfileId = profileId
        )
    }

}
