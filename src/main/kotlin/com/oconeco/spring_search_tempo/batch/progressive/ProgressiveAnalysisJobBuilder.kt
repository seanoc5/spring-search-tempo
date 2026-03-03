package com.oconeco.spring_search_tempo.batch.progressive

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
import com.oconeco.spring_search_tempo.batch.HeartbeatChunkListener
import com.oconeco.spring_search_tempo.batch.chunking.ChunkingStrategySelector
import com.oconeco.spring_search_tempo.batch.config.BatchTaskExecutorConfig.Companion.DEFAULT_THROTTLE_LIMIT
import com.oconeco.spring_search_tempo.batch.fscrawl.ChunkProcessor
import com.oconeco.spring_search_tempo.batch.fscrawl.ChunkReader
import com.oconeco.spring_search_tempo.batch.fscrawl.ChunkWriter
import com.oconeco.spring_search_tempo.batch.fscrawl.JobRunTrackingListener
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

/**
 * Builder for the progressive analysis job.
 *
 * Progressive analysis is the third phase of the decoupled crawl pipeline:
 * 1. Discovery - Fast filesystem enumeration, SKIP detection only
 * 2. Assignment - Full pattern matching to assign analysisStatus
 * 3. **Progressive Analysis** (this job) - LOCATE → INDEX → NLP → SEMANTIC
 *
 * Steps in this job:
 * 1. **IndexingStep** - Tika text extraction for INDEX+ files
 * 2. **ChunkingStep** - Split text into chunks using ChunkingStrategy
 * 3. **NLPStep** - NER, POS, sentiment for ANALYZE+ chunks (delegated to NLPProcessingJob)
 * 4. **EmbeddingStep** - Vector embeddings for SEMANTIC chunks (delegated to EmbeddingProcessingJob)
 *
 * Each step queries for items at its level that need processing, enabling:
 * - Incremental processing (only process what's changed)
 * - Restartability (resume from last successful step)
 * - Independent scaling (run steps separately if needed)
 */
@Component
class ProgressiveAnalysisJobBuilder(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val fileRepository: FSFileRepository,
    private val fileService: FSFileService,
    private val fileMapper: FSFileMapper,
    private val chunkService: ContentChunkService,
    private val textExtractionService: TextExtractionService,
    private val chunkingStrategySelector: ChunkingStrategySelector,
    private val jobRunTrackingListener: JobRunTrackingListener,
    private val heartbeatChunkListener: HeartbeatChunkListener,
    @Qualifier("nlpProcessingStep") private val nlpProcessingStep: Step,
    @Qualifier("embeddingProcessingStep") private val embeddingProcessingStep: Step,
    @Qualifier("stepTaskExecutor") private val stepTaskExecutor: TaskExecutor
) {
    companion object {
        private val log = LoggerFactory.getLogger(ProgressiveAnalysisJobBuilder::class.java)
    }

    /**
     * Build the full progressive analysis job.
     *
     * Runs all steps: Indexing → Chunking → NLP → Embedding
     *
     * @param processAll When true, process all eligible items; when false, only current job run
     * @return Configured Spring Batch Job
     */
    fun buildJob(processAll: Boolean = true): Job {
        log.info("Building progressive analysis job (processAll={})", processAll)

        return JobBuilder("progressiveAnalysisJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobRunTrackingListener)
            .start(buildIndexingStep())
            .next(buildChunkingStep(processAll))
            .next(nlpProcessingStep)
            .next(embeddingProcessingStep)
            .build()
    }

    /**
     * Build a job with only indexing and chunking steps.
     * Useful for initial content population without NLP/embedding overhead.
     */
    fun buildIndexingOnlyJob(processAll: Boolean = true): Job {
        log.info("Building indexing-only job (processAll={})", processAll)

        return JobBuilder("indexingOnlyJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobRunTrackingListener)
            .start(buildIndexingStep())
            .next(buildChunkingStep(processAll))
            .build()
    }

    /**
     * Build a job with NLP and embedding steps only.
     * Useful for reprocessing after NLP model updates.
     */
    fun buildSemanticOnlyJob(): Job {
        log.info("Building semantic-only job (NLP + Embedding)")

        return JobBuilder("semanticOnlyJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobRunTrackingListener)
            .start(nlpProcessingStep)
            .next(embeddingProcessingStep)
            .build()
    }

    /**
     * Build the indexing step.
     * Extracts text from files using Apache Tika.
     */
    private fun buildIndexingStep(): Step {
        log.info("Building indexing step")

        val reader = IndexingReader(
            fileRepository = fileRepository,
            fileMapper = fileMapper,
            pageSize = 50
        )

        val processor = IndexingProcessor(
            textExtractionService = textExtractionService,
            maxTextSize = 10_000_000L
        )

        val writer = IndexingWriter(
            fileRepository = fileRepository,
            fileMapper = fileMapper
        )

        return StepBuilder("indexingStep", jobRepository)
            .chunk<FSFileDTO, FSFileDTO>(50, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .listener(writer)
            .listener(heartbeatChunkListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }

    /**
     * Build the chunking step.
     * Splits extracted text into chunks using pluggable ChunkingStrategy.
     */
    private fun buildChunkingStep(processAll: Boolean): Step {
        log.info("Building chunking step (processAll={})", processAll)

        val reader = ChunkReader(
            fileService = fileService,
            pageSize = 50,
            processAll = processAll
        )

        val processor = ChunkProcessor(
            strategySelector = chunkingStrategySelector
        )

        val writer = ChunkWriter(
            chunkService = chunkService,
            fileService = fileService
        )

        return StepBuilder("chunkingStep", jobRepository)
            .chunk<FSFileDTO, List<ContentChunkDTO>>(10, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .listener(reader)
            .listener(heartbeatChunkListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }
}
