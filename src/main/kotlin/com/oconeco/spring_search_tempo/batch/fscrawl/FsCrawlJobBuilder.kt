package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.RecentCrawlSkipChecker
import com.oconeco.spring_search_tempo.base.service.StartPathValidator
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
import com.oconeco.spring_search_tempo.batch.HeartbeatChunkListener
import com.oconeco.spring_search_tempo.batch.nlp.NLPAutoTriggerListener
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
    private val fsFileRepository: com.oconeco.spring_search_tempo.base.repos.FSFileRepository,
    private val fileService: com.oconeco.spring_search_tempo.base.FSFileService,
    private val fileMapper: com.oconeco.spring_search_tempo.base.service.FSFileMapper,
    private val patternMatchingService: PatternMatchingService,
    private val textExtractionService: TextExtractionService,
    private val crawlConfigService: CrawlConfigService,
    private val chunkService: com.oconeco.spring_search_tempo.base.ContentChunkService,
    private val crawlCleanupListener: CrawlCleanupListener,
    private val jobRunTrackingListener: JobRunTrackingListener,
    private val nlpAutoTriggerListener: NLPAutoTriggerListener,
    private val heartbeatChunkListener: HeartbeatChunkListener,
    private val jobRunService: JobRunService
) {
    companion object {
        private val log = LoggerFactory.getLogger(FsCrawlJobBuilder::class.java)
    }

    /**
     * Build a complete batch job for a specific crawl definition.
     * Uses single-pass combined crawl strategy (ADR-004).
     *
     * @param crawl The crawl definition to build a job for
     * @param forceFullRecrawl When true, skip timestamp checks and re-process all items
     * @param crawlConfigId Optional database ID of the CrawlConfig entity (enables recent crawl skip logic)
     * @param freshnessHours Hours threshold for recent crawl skip (null = use default from config)
     * @param chunkProcessAll When true, chunk ALL files needing chunking regardless of job run (for backfill)
     * @return A configured Spring Batch Job
     */
    fun buildJob(
        crawl: CrawlDefinition,
        forceFullRecrawl: Boolean = false,
        crawlConfigId: Long? = null,
        freshnessHours: Int? = null,
        chunkProcessAll: Boolean = false
    ): Job {
        log.info("Building single-pass job for crawl: {} ({}) with {} start paths, forceFullRecrawl={}, crawlConfigId={}, freshnessHours={}, chunkProcessAll={}",
            crawl.name, crawl.label, crawl.startPaths.size, forceFullRecrawl, crawlConfigId, freshnessHours, chunkProcessAll)

        val effectivePatterns = crawlConfigService.getEffectivePatterns(crawl)
        val defaults = crawlConfigService.getDefaults()
        val maxDepth = crawl.getMaxDepth(defaults)
        val followLinks = crawl.getFollowLinks(defaults)

        // Determine effective freshness hours (param > config default)
        val effectiveFreshnessHours = freshnessHours ?: defaults.recentCrawlSkipHours

        // Create start paths for validation and crawling
        val startPaths = crawl.startPaths.map { Path(it) }

        // Create path validation listener to check and warn about invalid paths
        val pathValidationListener = PathValidationListener(startPaths, jobRunService)

        return JobBuilder("fsCrawlJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(crawlCleanupListener)  // Cleanup listener runs first (beforeJob order)
            .listener(jobRunTrackingListener)  // Creates JobRun record
            .listener(pathValidationListener)  // Validates paths and records warnings (needs jobRunId)
            .listener(nlpAutoTriggerListener)  // Auto-trigger NLP after crawl completes
            .start(buildCombinedCrawlStep(crawl, effectivePatterns, maxDepth, followLinks, forceFullRecrawl, crawlConfigId, effectiveFreshnessHours))
            .next(buildChunkingStep(crawl, chunkProcessAll))
            .build()
    }

    /**
     * Build the chunking step that splits file bodyText into ContentChunk.
     * The ChunkReader is registered as a listener to get jobRunId from the step context.
     *
     * @param crawl The crawl definition
     * @param processAll When true, process ALL files needing chunking regardless of job run
     */
    private fun buildChunkingStep(crawl: CrawlDefinition, processAll: Boolean = false): Step {
        log.info("Building chunking step for crawl: {} (processAll={})", crawl.name, processAll)

        val reader = createChunkReader(processAll)

        return StepBuilder("fsCrawlChunks_${crawl.name}", jobRepository)
            .chunk<com.oconeco.spring_search_tempo.base.model.FSFileDTO, List<com.oconeco.spring_search_tempo.base.model.ContentChunkDTO>>(10, transactionManager)
            .reader(reader)
            .processor(createChunkProcessor())
            .writer(createChunkWriter())
            .listener(reader)  // Register reader as listener to get jobRunId
            .listener(heartbeatChunkListener)  // Update heartbeat after each chunk
            .build()
    }

    /**
     * Create a chunk reader that reads FSFiles with bodyText.
     *
     * @param processAll When true, reads ALL files needing chunking; when false, only current job run
     */
    private fun createChunkReader(processAll: Boolean = false): ChunkReader {
        log.debug("Creating ChunkReader (processAll={})", processAll)
        return ChunkReader(
            fileService = fileService,
            pageSize = 50,
            processAll = processAll
        )
    }

    /**
     * Create a chunk processor that splits text into sentences.
     */
    private fun createChunkProcessor(): ItemProcessor<com.oconeco.spring_search_tempo.base.model.FSFileDTO, List<com.oconeco.spring_search_tempo.base.model.ContentChunkDTO>> {
        log.debug("Creating ChunkProcessor")
        return ChunkProcessor()
    }

    /**
     * Create a chunk writer that saves ContentChunk and marks files as chunked.
     */
    private fun createChunkWriter(): ItemWriter<List<com.oconeco.spring_search_tempo.base.model.ContentChunkDTO>> {
        log.debug("Creating ChunkWriter")
        return ChunkWriter(chunkService = chunkService, fileService = fileService)
    }

    /**
     * Build the combined crawl step (single-pass folders + files).
     * This step processes directories and their files together in one pass.
     */
    private fun buildCombinedCrawlStep(
        crawl: CrawlDefinition,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns,
        maxDepth: Int,
        followLinks: Boolean,
        forceFullRecrawl: Boolean = false,
        crawlConfigId: Long? = null,
        freshnessHours: Int = 24
    ): Step {
        log.info("Building combined crawl step for: {} with {} start paths (maxDepth={}, followLinks={}, forceFullRecrawl={}, crawlConfigId={}, freshnessHours={})",
            crawl.name, crawl.startPaths.size, maxDepth, followLinks, forceFullRecrawl, crawlConfigId, freshnessHours)

        val startPaths = crawl.startPaths.map { Path(it) }

        val writer = createCombinedWriter()

        return StepBuilder("fsCrawlCombined_${crawl.name}", jobRepository)
            .chunk<CombinedCrawlItem, CombinedCrawlResult>(100, transactionManager)
            .reader(createCombinedReader(startPaths, maxDepth, followLinks, effectivePatterns, crawlConfigId, freshnessHours))
            .processor(createCombinedProcessor(startPaths, effectivePatterns, forceFullRecrawl))
            .writer(writer)
            .listener(CrawlStepListener())
            .listener(writer) // Writer is also a step listener
            .listener(heartbeatChunkListener) // Update heartbeat after each chunk
            .build()
    }

    /**
     * Create a combined reader that walks directories and collects files.
     * Passes folder matcher to enable SKIP folder optimization at enumeration time.
     * Optionally creates a RecentCrawlSkipChecker if crawlConfigId is provided.
     */
    private fun createCombinedReader(
        startPaths: List<Path>,
        maxDepth: Int,
        followLinks: Boolean,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns,
        crawlConfigId: Long? = null,
        freshnessHours: Int = 24
    ): ItemReader<CombinedCrawlItem> {
        log.debug("Creating CombinedCrawlReader: {} startPaths, maxDepth={}, followLinks={}, crawlConfigId={}, freshnessHours={}",
            startPaths.size, maxDepth, followLinks, crawlConfigId, freshnessHours)

        // Create recent crawl checker only if we have a crawl config ID
        val recentCrawlChecker = if (crawlConfigId != null) {
            log.info("Creating RecentCrawlSkipChecker for crawlConfigId={}, freshnessHours={}",
                crawlConfigId, freshnessHours)
            RecentCrawlSkipChecker(
                fsFolderRepository = fsFolderRepository,
                currentCrawlConfigId = crawlConfigId,
                freshnessHours = freshnessHours
            )
        } else {
            log.debug("No crawlConfigId provided, recent crawl skip checking disabled")
            null
        }

        return CombinedCrawlReader(
            startPaths = startPaths,
            maxDepth = maxDepth,
            followLinks = followLinks,
            folderMatcher = { path ->
                patternMatchingService.determineFolderAnalysisStatus(
                    path.toString(),
                    effectivePatterns.folderPatterns,
                    parentStatus = null
                )
            },
            recentCrawlChecker = recentCrawlChecker
        )
    }

    /**
     * Create a combined processor that handles both folders and files.
     */
    private fun createCombinedProcessor(
        startPaths: List<Path>,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns,
        forceFullRecrawl: Boolean = false
    ): ItemProcessor<CombinedCrawlItem, CombinedCrawlResult> {
        log.debug("Creating CombinedCrawlProcessor with pattern matching and caching (forceFullRecrawl={})", forceFullRecrawl)
        return CombinedCrawlProcessor(
            startPaths = startPaths,
            effectivePatterns = effectivePatterns,
            folderRepository = fsFolderRepository,
            fileRepository = fsFileRepository,
            folderMapper = folderMapper,
            fileMapper = fileMapper,
            patternMatchingService = patternMatchingService,
            textExtractionService = textExtractionService,
            forceFullRecrawl = forceFullRecrawl
        )
    }

    /**
     * Create a combined writer that persists folders and files.
     */
    private fun createCombinedWriter(): ItemWriter<CombinedCrawlResult> {
        log.debug("Creating CombinedCrawlWriter")
        return CombinedCrawlWriter(
            folderService = folderService,
            fileService = fileService,
            folderRepository = fsFolderRepository,
            fileRepository = fsFileRepository,
            folderMapper = folderMapper,
            fileMapper = fileMapper
        )
    }
}
