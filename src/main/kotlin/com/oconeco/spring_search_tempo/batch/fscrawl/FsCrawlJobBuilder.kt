package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
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
    private val nlpAutoTriggerListener: NLPAutoTriggerListener
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
     * @return A configured Spring Batch Job
     */
    fun buildJob(crawl: CrawlDefinition, forceFullRecrawl: Boolean = false): Job {
        log.info("Building single-pass job for crawl: {} ({}) with {} start paths, forceFullRecrawl={}",
            crawl.name, crawl.label, crawl.startPaths.size, forceFullRecrawl)

        val effectivePatterns = crawlConfigService.getEffectivePatterns(crawl)
        val defaults = crawlConfigService.getDefaults()
        val maxDepth = crawl.getMaxDepth(defaults)
        val followLinks = crawl.getFollowLinks(defaults)

        return JobBuilder("fsCrawlJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(crawlCleanupListener)  // Cleanup listener runs first (beforeJob order)
            .listener(jobRunTrackingListener)
            .listener(nlpAutoTriggerListener)  // Auto-trigger NLP after crawl completes
            .start(buildCombinedCrawlStep(crawl, effectivePatterns, maxDepth, followLinks, forceFullRecrawl))
            .next(buildChunkingStep(crawl))
            .build()
    }

    /**
     * Build the chunking step that splits file bodyText into ContentChunk.
     * The ChunkReader is registered as a listener to get jobRunId from the step context.
     */
    private fun buildChunkingStep(crawl: CrawlDefinition): Step {
        log.info("Building chunking step for crawl: {}", crawl.name)

        val reader = createChunkReader()

        return StepBuilder("fsCrawlChunks_${crawl.name}", jobRepository)
            .chunk<com.oconeco.spring_search_tempo.base.model.FSFileDTO, List<com.oconeco.spring_search_tempo.base.model.ContentChunkDTO>>(10, transactionManager)
            .reader(reader)
            .processor(createChunkProcessor())
            .writer(createChunkWriter())
            .listener(reader)  // Register reader as listener to get jobRunId
            .build()
    }

    /**
     * Create a chunk reader that reads FSFiles with bodyText for the current job run.
     */
    private fun createChunkReader(): ChunkReader {
        log.debug("Creating ChunkReader")
        return ChunkReader(
            fileService = fileService,
            pageSize = 50
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
        forceFullRecrawl: Boolean = false
    ): Step {
        log.info("Building combined crawl step for: {} with {} start paths (maxDepth={}, followLinks={}, forceFullRecrawl={})",
            crawl.name, crawl.startPaths.size, maxDepth, followLinks, forceFullRecrawl)

        val startPaths = crawl.startPaths.map { Path(it) }

        val writer = createCombinedWriter()

        return StepBuilder("fsCrawlCombined_${crawl.name}", jobRepository)
            .chunk<CombinedCrawlItem, CombinedCrawlResult>(100, transactionManager)
            .reader(createCombinedReader(startPaths, maxDepth, followLinks, effectivePatterns))
            .processor(createCombinedProcessor(startPaths, effectivePatterns, forceFullRecrawl))
            .writer(writer)
            .listener(CrawlStepListener())
            .listener(writer) // Writer is also a step listener
            .build()
    }

    /**
     * Create a combined reader that walks directories and collects files.
     * Passes folder matcher to enable SKIP folder optimization at enumeration time.
     */
    private fun createCombinedReader(
        startPaths: List<Path>,
        maxDepth: Int,
        followLinks: Boolean,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns
    ): ItemReader<CombinedCrawlItem> {
        log.debug("Creating CombinedCrawlReader: {} startPaths, maxDepth={}, followLinks={}",
            startPaths.size, maxDepth, followLinks)
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
            }
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
            fileService = fileService
        )
    }
}
