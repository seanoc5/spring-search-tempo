package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
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
    private val chunkService: com.oconeco.spring_search_tempo.base.ContentChunksService
) {
    companion object {
        private val log = LoggerFactory.getLogger(FsCrawlJobBuilder::class.java)
    }

    /**
     * Build a complete batch job for a specific crawl definition.
     * Uses single-pass combined crawl strategy (ADR-004).
     *
     * @param crawl The crawl definition to build a job for
     * @return A configured Spring Batch Job
     */
    fun buildJob(crawl: CrawlDefinition): Job {
        log.info("Building single-pass job for crawl: {} ({})", crawl.name, crawl.label)

        val effectivePatterns = crawlConfigService.getEffectivePatterns(crawl)
        val defaults = crawlConfigService.getDefaults()
        val maxDepth = crawl.getMaxDepth(defaults)
        val followLinks = crawl.getFollowLinks(defaults)

        return JobBuilder("fsCrawlJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .start(buildCombinedCrawlStep(crawl, effectivePatterns, maxDepth, followLinks))
            .next(buildChunkingStep(crawl))
            .build()
    }

    /**
     * Build a complete batch job using the legacy two-phase approach.
     * Kept for comparison/rollback purposes (see ADR-004).
     *
     * @param crawl The crawl definition to build a job for
     * @return A configured Spring Batch Job
     */
    fun buildTwoPhaseJob(crawl: CrawlDefinition): Job {
        log.info("Building two-phase job for crawl: {} ({})", crawl.name, crawl.label)

        val effectivePatterns = crawlConfigService.getEffectivePatterns(crawl)
        val defaults = crawlConfigService.getDefaults()
        val maxDepth = crawl.getMaxDepth(defaults)
        val followLinks = crawl.getFollowLinks(defaults)

        return JobBuilder("fsCrawlJob_twoPhase", jobRepository)
            .incrementer(RunIdIncrementer())
            .start(buildFoldersStep(crawl, effectivePatterns, maxDepth, followLinks))
            .next(buildFilesStep(crawl, effectivePatterns))
            .next(buildChunkingStep(crawl))
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

    /**
     * Build the files processing step for a crawl.
     */
    private fun buildFilesStep(
        crawl: CrawlDefinition,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns
    ): Step {
        log.info("Building files step for crawl: {}", crawl.name)

        val startPath = Path(crawl.startPath)

        return StepBuilder("fsCrawlFiles_${crawl.name}", jobRepository)
            .chunk<Path, com.oconeco.spring_search_tempo.base.model.FSFileDTO>(100, transactionManager)
            .reader(createFileReader())
            .processor(createFileProcessor(startPath, effectivePatterns))
            .writer(createFileWriter())
            .build()
    }

    /**
     * Create a file reader that reads files from indexed folders.
     */
    private fun createFileReader(): ItemReader<Path> {
        log.debug("Creating FileReader")
        return FileReader(
            folderRepository = fsFolderRepository,
            batchSize = 100
        )
    }

    /**
     * Create a file processor with pattern matching and text extraction.
     */
    private fun createFileProcessor(
        startPath: Path,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns
    ): ItemProcessor<Path, com.oconeco.spring_search_tempo.base.model.FSFileDTO> {
        log.debug("Creating FileProcessor with pattern matching and Tika text extraction")
        return FileProcessor(
            startPath = startPath,
            fileRepository = fsFileRepository,
            folderRepository = fsFolderRepository,
            fileMapper = fileMapper,
            patternMatchingService = patternMatchingService,
            textExtractionService = textExtractionService,
            filePatterns = effectivePatterns.filePatterns
        )
    }

    /**
     * Create a file writer.
     */
    private fun createFileWriter(): ItemWriter<com.oconeco.spring_search_tempo.base.model.FSFileDTO> {
        log.debug("Creating FileWriter")
        return FileWriter(fileService = fileService)
    }

    /**
     * Build the chunking step that splits file bodyText into ContentChunks.
     */
    private fun buildChunkingStep(crawl: CrawlDefinition): Step {
        log.info("Building chunking step for crawl: {}", crawl.name)

        return StepBuilder("fsCrawlChunks_${crawl.name}", jobRepository)
            .chunk<com.oconeco.spring_search_tempo.base.model.FSFileDTO, List<com.oconeco.spring_search_tempo.base.model.ContentChunksDTO>>(10, transactionManager)
            .reader(createChunkReader())
            .processor(createChunkProcessor())
            .writer(createChunkWriter())
            .build()
    }

    /**
     * Create a chunk reader that reads FSFiles with bodyText.
     */
    private fun createChunkReader(): ItemReader<com.oconeco.spring_search_tempo.base.model.FSFileDTO> {
        log.debug("Creating ChunkReader")
        return ChunkReader(
            fileService = fileService,
            pageSize = 50
        )
    }

    /**
     * Create a chunk processor that splits text into sentences.
     */
    private fun createChunkProcessor(): ItemProcessor<com.oconeco.spring_search_tempo.base.model.FSFileDTO, List<com.oconeco.spring_search_tempo.base.model.ContentChunksDTO>> {
        log.debug("Creating ChunkProcessor")
        return ChunkProcessor()
    }

    /**
     * Create a chunk writer that saves ContentChunks.
     */
    private fun createChunkWriter(): ItemWriter<List<com.oconeco.spring_search_tempo.base.model.ContentChunksDTO>> {
        log.debug("Creating ChunkWriter")
        return ChunkWriter(chunkService = chunkService)
    }

    /**
     * Build the combined crawl step (single-pass folders + files).
     * This step processes directories and their files together in one pass.
     */
    private fun buildCombinedCrawlStep(
        crawl: CrawlDefinition,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns,
        maxDepth: Int,
        followLinks: Boolean
    ): Step {
        log.info("Building combined crawl step for: {} (maxDepth={}, followLinks={})",
            crawl.name, maxDepth, followLinks)

        val startPath = Path(crawl.startPath)

        return StepBuilder("fsCrawlCombined_${crawl.name}", jobRepository)
            .chunk<CombinedCrawlItem, CombinedCrawlResult>(100, transactionManager)
            .reader(createCombinedReader(startPath, maxDepth, followLinks))
            .processor(createCombinedProcessor(startPath, effectivePatterns))
            .writer(createCombinedWriter())
            .build()
    }

    /**
     * Create a combined reader that walks directories and collects files.
     */
    private fun createCombinedReader(
        startPath: Path,
        maxDepth: Int,
        followLinks: Boolean
    ): ItemReader<CombinedCrawlItem> {
        log.debug("Creating CombinedCrawlReader: startPath={}, maxDepth={}, followLinks={}",
            startPath, maxDepth, followLinks)
        return CombinedCrawlReader(
            startPath = startPath,
            maxDepth = maxDepth,
            followLinks = followLinks
        )
    }

    /**
     * Create a combined processor that handles both folders and files.
     */
    private fun createCombinedProcessor(
        startPath: Path,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns
    ): ItemProcessor<CombinedCrawlItem, CombinedCrawlResult> {
        log.debug("Creating CombinedCrawlProcessor with pattern matching and caching")
        return CombinedCrawlProcessor(
            startPath = startPath,
            effectivePatterns = effectivePatterns,
            folderRepository = fsFolderRepository,
            fileRepository = fsFileRepository,
            folderMapper = folderMapper,
            fileMapper = fileMapper,
            patternMatchingService = patternMatchingService,
            textExtractionService = textExtractionService
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
