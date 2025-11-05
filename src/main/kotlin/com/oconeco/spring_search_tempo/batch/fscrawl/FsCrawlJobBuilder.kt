package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.config.CrawlConfiguration
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemReader
import org.springframework.batch.item.ItemWriter
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Builder for creating file system crawl batch jobs dynamically.
 * Creates a job for each crawl definition with appropriate pattern matching.
 */
@Component
class FsCrawlJobBuilder(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val fsFolderRepository: FSFolderRepository,
    private val folderService: FSFolderService,
    private val folderMapper: FSFolderMapper,
    private val patternMatchingService: PatternMatchingService,
    private val crawlConfiguration: CrawlConfiguration
) {
    companion object {
        private val log = LoggerFactory.getLogger(FsCrawlJobBuilder::class.java)
    }

    /**
     * Build a complete batch job for a specific crawl definition.
     *
     * @param crawl The crawl definition to build a job for
     * @return A configured Spring Batch Job
     */
    fun buildJob(crawl: CrawlDefinition): Job {
        log.info("Building job for crawl: {} ({})", crawl.name, crawl.label)

        val effectivePatterns = crawlConfiguration.getEffectivePatterns(crawl)
        val maxDepth = crawl.getMaxDepth(crawlConfiguration.defaults)
        val followLinks = crawl.getFollowLinks(crawlConfiguration.defaults)

        return JobBuilder("fsCrawlJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .start(buildFoldersStep(crawl, effectivePatterns, maxDepth, followLinks))
            .build()
    }

    /**
     * Build the folders processing step for a crawl.
     */
    private fun buildFoldersStep(
        crawl: CrawlDefinition,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns,
        maxDepth: Int,
        followLinks: Boolean
    ): Step {
        log.info("Building folders step for crawl: {} with maxDepth: {}, followLinks: {}",
            crawl.name, maxDepth, followLinks)

        val startPath = Path(crawl.startPath)

        return StepBuilder("fsCrawlFolders_${crawl.name}", jobRepository)
            .chunk<Path, FSFolderDTO>(1000, transactionManager)
            .reader(createFolderReader(startPath, maxDepth, followLinks))
            .processor(createFolderProcessor(startPath, effectivePatterns))
            .writer(createFolderWriter())
            .build()
    }

    /**
     * Create a folder reader for the given parameters.
     */
    private fun createFolderReader(
        startPath: Path,
        maxDepth: Int,
        followLinks: Boolean
    ): ItemReader<Path> {
        log.debug("Creating FolderReader: startPath={}, maxDepth={}, followLinks={}",
            startPath, maxDepth, followLinks)
        return FolderReader(
            startPath = startPath,
            maxDepth = maxDepth,
            followLinks = followLinks
        )
    }

    /**
     * Create a folder processor with pattern matching support.
     */
    private fun createFolderProcessor(
        startPath: Path,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns
    ): ItemProcessor<Path, FSFolderDTO> {
        log.debug("Creating FolderProcessor with pattern matching")
        return FolderProcessor(
            startPath = startPath,
            folderRepository = fsFolderRepository,
            folderMapper = folderMapper,
            patternMatchingService = patternMatchingService,
            folderPatterns = effectivePatterns.folderPatterns
        )
    }

    /**
     * Create a folder writer.
     */
    private fun createFolderWriter(): ItemWriter<FSFolderDTO> {
        log.debug("Creating FolderWriter")
        return FolderWriter(folderService = folderService)
    }
}
