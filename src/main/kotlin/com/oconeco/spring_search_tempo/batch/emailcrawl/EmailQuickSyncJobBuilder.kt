package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailCategorizationService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.service.ContentChunkMapper
import com.oconeco.spring_search_tempo.base.service.EmailTextExtractionService
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.oconeco.spring_search_tempo.base.service.EmbeddingService
import com.oconeco.spring_search_tempo.base.service.NLPService
import com.oconeco.spring_search_tempo.batch.HeartbeatChunkListener
import com.oconeco.spring_search_tempo.batch.ProgressTrackingItemWriteListener
import com.oconeco.spring_search_tempo.batch.embedding.EmbeddingChunkProcessor
import com.oconeco.spring_search_tempo.batch.nlp.NLPChunkProcessor
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.integration.async.AsyncItemProcessor
import org.springframework.batch.integration.async.AsyncItemWriter
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemReader
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.repeat.RepeatStatus
import com.oconeco.spring_search_tempo.batch.config.BatchTaskExecutorConfig.Companion.DEFAULT_THROTTLE_LIMIT
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import java.time.OffsetDateTime
import java.util.concurrent.Future


/**
 * Builder for creating email sync batch jobs with multi-pass architecture.
 *
 * Creates a job for each email account with:
 * 1. Pass 1: Header sync steps for each folder (fast - headers only)
 * 2. Pass 2: Body enrichment step (slower - fetches bodies, can be parallelized)
 * 3. Pass 3: Chunking step (splits body text, filtered to "interesting" messages)
 * 4. Pass 4: NLP step (Stanford CoreNLP on email chunks)
 * 5. Pass 5: Categorization step (classify emails by type)
 * 6. Pass 6: Embedding step (vector embeddings via Ollama)
 */
@Component
class EmailQuickSyncJobBuilder(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val imapConnectionService: ImapConnectionService,
    private val emailAccountService: EmailAccountService,
    private val emailMessageService: EmailMessageService,
    private val emailFolderService: EmailFolderService,
    private val emailTextExtractionService: EmailTextExtractionService,
    private val chunkService: ContentChunkService,
    private val categorizationService: EmailCategorizationService,
    private val objectMapper: ObjectMapper,
    private val jobRunService: JobRunService,
    private val emailJobRunTrackingListener: EmailJobRunTrackingListener,
    private val heartbeatChunkListener: HeartbeatChunkListener,
    private val progressTrackingItemWriteListener: ProgressTrackingItemWriteListener<Any>,
    private val nlpService: NLPService,
    private val embeddingService: EmbeddingService,
    private val contentChunkMapper: ContentChunkMapper,
    private val contentChunkRepository: ContentChunkRepository,
    @Qualifier("stepTaskExecutor") private val stepTaskExecutor: TaskExecutor,
    @Qualifier("asyncItemExecutor") private val asyncItemExecutor: TaskExecutor
) {
    companion object {
        private val log = LoggerFactory.getLogger(EmailQuickSyncJobBuilder::class.java)
    }

    /**
     * Build a complete batch job for an email account.
     *
     * Job structure (6-pass pipeline):
     * 1. Header sync for each folder (fast - only envelope data)
     * 2. Body enrichment (slower - fetches full message bodies)
     * 3. Chunking (splits body text, filtered to "interesting" messages)
     * 4. NLP (Stanford CoreNLP on email chunks)
     * 5. Categorization (classify emails by type)
     * 6. Embedding (vector embeddings via Ollama, skipped if unavailable)
     *
     * @param account The email account to sync
     * @param folders The folder names to sync (e.g., ["INBOX", "Sent"])
     * @param forceFullSync If true, ignore lastSyncUid and fetch all messages
     * @param forceRefresh If true, re-process already-processed records (chunks, NLP)
     * @param interestingDays How far back to look for "interesting" messages (default 7)
     * @param parallelConfig Configuration for parallel processing (default: serial)
     * @return A configured Spring Batch Job
     */
    fun buildJob(
        account: EmailAccountDTO,
        folders: List<String>,
        forceFullSync: Boolean = false,
        forceRefresh: Boolean = false,
        interestingDays: Int = 7,
        parallelConfig: ParallelizationConfig = ParallelizationConfig()
    ): Job {
        log.info(
            "Building email {} job for account: {} ({}) with {} folders (6-pass, {}, forceRefresh={}, interestingDays={})",
            if (forceFullSync) "FULL sync" else "quick sync",
            account.email,
            account.label,
            folders.size,
            parallelConfig.modeName,
            forceRefresh,
            interestingDays
        )

        val jobName = "emailQuickSync_${account.id}"
        val cutoffDate = OffsetDateTime.now().minusDays(interestingDays.toLong())

        val jobBuilder = JobBuilder(jobName, jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(emailJobRunTrackingListener)  // Track job run with heartbeat

        // Pass 1: Build header sync steps for each folder (fast)
        var currentFlow = jobBuilder.start(buildHeaderSyncStep(account, folders.first(), forceFullSync))

        folders.drop(1).forEach { folder ->
            currentFlow = currentFlow.next(buildHeaderSyncStep(account, folder, forceFullSync))
        }

        // Pass 2: Body enrichment step (slower, processes all HEADERS_ONLY messages)
        currentFlow = currentFlow.next(buildBodyEnrichmentStep(account, parallelConfig))

        // Pass 3: Chunking step (filtered to "interesting" messages)
        currentFlow = currentFlow.next(buildChunkingStep(account, cutoffDate, forceRefresh))

        // Pass 4: NLP step (Stanford CoreNLP on email chunks)
        currentFlow = currentFlow.next(buildNLPStep(account, cutoffDate, forceRefresh))

        // Pass 5: Categorization step
        currentFlow = currentFlow.next(buildCategorizationStep(account))

        // Pass 6: Embedding step (skipped gracefully if Ollama unavailable)
        return currentFlow
            .next(buildEmbeddingStep(account, cutoffDate, forceRefresh))
            .build()
    }

    // ==================== PASS 1: HEADER SYNC ====================

    /**
     * Build a header sync step for a specific folder (Pass 1).
     * Fast: only fetches envelope data, no body content.
     */
    private fun buildHeaderSyncStep(account: EmailAccountDTO, folderName: String, forceFullSync: Boolean = false): Step {
        log.info("Building header sync step for {}/{} (forceFullSync={})", account.email, folderName, forceFullSync)

        val reader = createHeaderSyncReader(account, folderName, forceFullSync)
        val writer = createHeaderSyncWriter(account.id!!, folderName)

        return StepBuilder("emailHeaderSync_${account.id}_$folderName", jobRepository)
            .chunk<ImapMessageWrapper, EmailMessageDTO>(100, transactionManager)  // Larger chunks since no body fetch
            .reader(reader)
            .processor(createHeaderSyncProcessor())
            .writer(writer)
            .listener(reader)  // Reader needs beforeStep for heartbeat during initialization
            .listener(writer)  // Writer is also a step listener for sync state updates
            .listener(heartbeatChunkListener)  // Update heartbeat after each chunk
            .listener(progressTrackingItemWriteListener)  // Track progress for UI
            .build()
    }

    private fun createHeaderSyncReader(
        account: EmailAccountDTO,
        folderName: String,
        forceFullSync: Boolean = false
    ): EmailQuickSyncReader {
        return EmailQuickSyncReader(
            account = account,
            folderName = folderName,
            imapConnectionService = imapConnectionService,
            emailFolderService = emailFolderService,
            emailMessageService = emailMessageService,
            jobRunService = jobRunService,
            forceFullSync = forceFullSync
        )
    }

    private fun createHeaderSyncProcessor(): ItemProcessor<ImapMessageWrapper, EmailMessageDTO> {
        return EmailQuickSyncProcessor(objectMapper = objectMapper)
    }

    private fun createHeaderSyncWriter(accountId: Long, folderName: String): EmailQuickSyncWriter {
        return EmailQuickSyncWriter(
            emailMessageService = emailMessageService,
            emailFolderService = emailFolderService,
            accountId = accountId,
            folderName = folderName
        )
    }

    // ==================== PASS 2: BODY ENRICHMENT ====================

    /**
     * Build the body enrichment step (Pass 2).
     * Slower: fetches full message bodies from IMAP for all HEADERS_ONLY messages.
     *
     * Supports multiple parallelization modes based on config:
     * - Serial: Single-threaded (default)
     * - TaskExecutor: Multi-threaded chunk processing
     * - AsyncItemProcessor: Async item processing
     * - Combined: Both strategies
     */
    private fun buildBodyEnrichmentStep(
        account: EmailAccountDTO,
        parallelConfig: ParallelizationConfig = ParallelizationConfig()
    ): Step {
        log.info("Building body enrichment step for {} with mode: {}",
            account.email, parallelConfig.modeName)

        return when {
            parallelConfig.itemAsync -> buildAsyncBodyEnrichmentStep(account, parallelConfig)
            parallelConfig.stepThreads > 1 -> buildMultiThreadedBodyEnrichmentStep(account, parallelConfig)
            else -> buildSerialBodyEnrichmentStep(account, parallelConfig)
        }
    }

    /**
     * Serial body enrichment step (original behavior).
     */
    private fun buildSerialBodyEnrichmentStep(
        account: EmailAccountDTO,
        config: ParallelizationConfig
    ): Step {
        log.info("Building SERIAL body enrichment step for {}", account.email)

        val processor = createBodyEnrichmentProcessor()

        return StepBuilder("emailBodyEnrich_${account.id}", jobRepository)
            .chunk<EmailMessageDTO, BodyEnrichmentResult>(config.chunkSize, transactionManager)
            .reader(createBodyEnrichmentReader(account.id!!))
            .processor(processor)
            .writer(createBodyEnrichmentWriter())
            .listener(processor)  // Processor manages IMAP connections
            .listener(heartbeatChunkListener)  // Update heartbeat after each chunk
            .listener(progressTrackingItemWriteListener)  // Track progress for UI
            .build()
    }

    /**
     * Multi-threaded body enrichment step using TaskExecutor.
     * Multiple chunks processed in parallel.
     */
    private fun buildMultiThreadedBodyEnrichmentStep(
        account: EmailAccountDTO,
        config: ParallelizationConfig
    ): Step {
        log.info("Building MULTI-THREADED body enrichment step for {} with {} threads",
            account.email, config.stepThreads)

        val processor = createThreadSafeBodyEnrichmentProcessor()

        return StepBuilder("emailBodyEnrich_${account.id}", jobRepository)
            .chunk<EmailMessageDTO, BodyEnrichmentResult>(config.chunkSize, transactionManager)
            .reader(createBodyEnrichmentReader(account.id!!))
            .processor(processor)
            .writer(createBodyEnrichmentWriter())
            .listener(processor)
            .listener(heartbeatChunkListener)
            .listener(progressTrackingItemWriteListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(config.stepThreads)
            .build()
    }

    /**
     * Async body enrichment step using AsyncItemProcessor.
     * Items processed asynchronously, optionally with multi-threaded chunks.
     */
    private fun buildAsyncBodyEnrichmentStep(
        account: EmailAccountDTO,
        config: ParallelizationConfig
    ): Step {
        log.info("Building ASYNC body enrichment step for {} (asyncThreads={}, stepThreads={})",
            account.email, config.asyncThreads, config.stepThreads)

        val threadSafeProcessor = createThreadSafeBodyEnrichmentProcessor()

        // Wrap in AsyncItemProcessor
        val asyncProcessor = AsyncItemProcessor<EmailMessageDTO, BodyEnrichmentResult>().apply {
            setDelegate(threadSafeProcessor)
            setTaskExecutor(asyncItemExecutor)
        }

        // Wrap writer in AsyncItemWriter
        val asyncWriter = AsyncItemWriter<BodyEnrichmentResult>().apply {
            setDelegate(createBodyEnrichmentWriter())
        }

        val builder = StepBuilder("emailBodyEnrich_${account.id}", jobRepository)
            .chunk<EmailMessageDTO, Future<BodyEnrichmentResult>>(config.chunkSize, transactionManager)
            .reader(createBodyEnrichmentReader(account.id!!))
            .processor(asyncProcessor)
            .writer(asyncWriter)
            .listener(threadSafeProcessor)  // Still need lifecycle callbacks
            .listener(heartbeatChunkListener)

        val builderWithProgressListener = builder.listener(progressTrackingItemWriteListener)

        // Optionally add step-level parallelism too
        return if (config.stepThreads > 1) {
            builderWithProgressListener.taskExecutor(stepTaskExecutor)
                .throttleLimit(config.stepThreads)
                .build()
        } else {
            builderWithProgressListener.build()
        }
    }

    private fun createBodyEnrichmentReader(accountId: Long): ItemReader<EmailMessageDTO> {
        return BodyEnrichmentReader(
            emailMessageService = emailMessageService,
            accountId = accountId,
            pageSize = 100
        )
    }

    private fun createBodyEnrichmentProcessor(): BodyEnrichmentProcessor {
        return BodyEnrichmentProcessor(
            imapConnectionService = imapConnectionService,
            emailAccountService = emailAccountService,
            emailFolderService = emailFolderService,
            emailTextExtractionService = emailTextExtractionService,
            objectMapper = objectMapper
        )
    }

    private fun createThreadSafeBodyEnrichmentProcessor(): ThreadSafeBodyEnrichmentProcessor {
        return ThreadSafeBodyEnrichmentProcessor(
            imapConnectionService = imapConnectionService,
            emailAccountService = emailAccountService,
            emailFolderService = emailFolderService,
            emailTextExtractionService = emailTextExtractionService,
            objectMapper = objectMapper
        )
    }

    private fun createBodyEnrichmentWriter(): ItemWriter<BodyEnrichmentResult> {
        return BodyEnrichmentWriter(emailMessageService = emailMessageService)
    }

    // ==================== PASS 3: CHUNKING ====================

    /**
     * Build the chunking step that splits email body text into ContentChunks.
     * Filtered to "interesting" messages: within cutoff date, not junk-tagged.
     */
    private fun buildChunkingStep(
        account: EmailAccountDTO,
        cutoffDate: OffsetDateTime,
        forceRefresh: Boolean
    ): Step {
        log.info("Building chunking step for email account: {} (cutoff={}, forceRefresh={})",
            account.email, cutoffDate, forceRefresh)

        return StepBuilder("emailChunking_${account.id}", jobRepository)
            .chunk<EmailMessageDTO, List<ContentChunkDTO>>(10, transactionManager)
            .reader(createChunkReader(account.id!!, cutoffDate, forceRefresh))
            .processor(createChunkProcessor())
            .writer(createChunkWriter(forceRefresh))
            .listener(heartbeatChunkListener)  // Update heartbeat after each chunk
            .listener(progressTrackingItemWriteListener)  // Track progress for UI
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }

    /**
     * Create a chunk reader that reads "interesting" email messages for chunking.
     */
    private fun createChunkReader(
        accountId: Long,
        cutoffDate: OffsetDateTime,
        forceRefresh: Boolean
    ): ItemReader<EmailMessageDTO> {
        log.debug("Creating EmailChunkReader for account {} (cutoff={}, forceRefresh={})",
            accountId, cutoffDate, forceRefresh)
        return EmailChunkReader(
            emailMessageService = emailMessageService,
            accountId = accountId,
            cutoffDate = cutoffDate,
            forceRefresh = forceRefresh,
            pageSize = 50
        )
    }

    /**
     * Create a chunk processor that splits body text into sentences.
     */
    private fun createChunkProcessor(): ItemProcessor<EmailMessageDTO, List<ContentChunkDTO>> {
        log.debug("Creating EmailChunkProcessor")
        return EmailChunkProcessor()
    }

    /**
     * Create a chunk writer that saves ContentChunks.
     */
    private fun createChunkWriter(forceRefresh: Boolean): ItemWriter<List<ContentChunkDTO>> {
        log.debug("Creating EmailChunkWriter (forceRefresh={})", forceRefresh)
        return EmailChunkWriter(
            chunkService = chunkService,
            contentChunkRepository = if (forceRefresh) contentChunkRepository else null,
            forceRefresh = forceRefresh
        )
    }

    // ==================== PASS 4: NLP ====================

    /**
     * Build the NLP step that processes email chunks through Stanford CoreNLP.
     * Filtered to "interesting" email chunks: within cutoff date, not junk-tagged.
     */
    private fun buildNLPStep(
        account: EmailAccountDTO,
        cutoffDate: OffsetDateTime,
        forceRefresh: Boolean
    ): Step {
        log.info("Building NLP step for email account: {} (cutoff={}, forceRefresh={})",
            account.email, cutoffDate, forceRefresh)

        val nlpProcessor = NLPChunkProcessor(nlpService, objectMapper, contentChunkMapper)

        return StepBuilder("emailNLP_${account.id}", jobRepository)
            .chunk<ContentChunk, ContentChunkDTO>(10, transactionManager)
            .reader(createNLPReader(account.id!!, cutoffDate, forceRefresh))
            .processor(ItemProcessor { entity ->
                val dto = contentChunkMapper.toDto(entity)
                nlpProcessor.process(dto)
            })
            .writer(createNLPWriter())
            .listener(heartbeatChunkListener)
            .listener(progressTrackingItemWriteListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }

    private fun createNLPReader(
        accountId: Long,
        cutoffDate: OffsetDateTime,
        forceRefresh: Boolean
    ): ItemReader<ContentChunk> {
        log.debug("Creating EmailNLPReader for account {} (cutoff={}, forceRefresh={})",
            accountId, cutoffDate, forceRefresh)
        return EmailNLPReader(
            contentChunkRepository = contentChunkRepository,
            accountId = accountId,
            cutoffDate = cutoffDate,
            forceRefresh = forceRefresh,
            pageSize = 50
        )
    }

    private fun createNLPWriter(): ItemWriter<ContentChunkDTO> {
        log.debug("Creating EmailNLPWriter")
        return EmailNLPWriter(contentChunkRepository = contentChunkRepository)
    }

    // ==================== PASS 5: CATEGORIZATION ====================

    /**
     * Build the categorization step that classifies emails by type.
     */
    private fun buildCategorizationStep(account: EmailAccountDTO): Step {
        log.info("Building categorization step for email account: {}", account.email)

        return StepBuilder("emailCategorization_${account.id}", jobRepository)
            .chunk<EmailMessageDTO, EmailMessageDTO>(50, transactionManager)
            .reader(createCategorizationReader(account.id!!))
            .processor(createCategorizationProcessor())
            .writer(createCategorizationWriter())
            .listener(heartbeatChunkListener)
            .listener(progressTrackingItemWriteListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }

    private fun createCategorizationReader(accountId: Long): ItemReader<EmailMessageDTO> {
        log.debug("Creating EmailCategorizationReader for account {}", accountId)
        return EmailCategorizationReader(
            emailMessageService = emailMessageService,
            accountId = accountId,
            pageSize = 100
        )
    }

    private fun createCategorizationProcessor(): ItemProcessor<EmailMessageDTO, EmailMessageDTO> {
        log.debug("Creating EmailCategorizationProcessor")
        return EmailCategorizationProcessor(categorizationService = categorizationService)
    }

    private fun createCategorizationWriter(): ItemWriter<EmailMessageDTO> {
        log.debug("Creating EmailCategorizationWriter")
        return EmailCategorizationWriter(emailMessageService = emailMessageService)
    }

    // ==================== PASS 6: EMBEDDING ====================

    /**
     * Build the embedding step that generates vector embeddings for email chunks.
     *
     * If the embedding service is unavailable, builds a tasklet that logs a warning
     * and returns FINISHED (graceful degradation).
     */
    private fun buildEmbeddingStep(
        account: EmailAccountDTO,
        cutoffDate: OffsetDateTime,
        forceRefresh: Boolean
    ): Step {
        log.info("Building embedding step for email account: {} (cutoff={}, forceRefresh={})",
            account.email, cutoffDate, forceRefresh)

        if (!embeddingService.isAvailable()) {
            log.warn("Embedding service unavailable - building skip tasklet for account {}", account.email)
            return StepBuilder("emailEmbedding_${account.id}", jobRepository)
                .tasklet(Tasklet { _, _ ->
                    log.warn("Embedding step skipped for account {} - embedding service (Ollama) is not available",
                        account.email)
                    RepeatStatus.FINISHED
                }, transactionManager)
                .build()
        }

        val processor = EmbeddingChunkProcessor(embeddingService)

        return StepBuilder("emailEmbedding_${account.id}", jobRepository)
            .chunk<ContentChunk, ContentChunkDTO>(10, transactionManager)
            .reader(createEmbeddingReader(account.id!!, cutoffDate, forceRefresh))
            .processor(ItemProcessor { entity ->
                val dto = contentChunkMapper.toDto(entity)
                processor.process(dto)
            })
            .writer(createEmbeddingWriter())
            .listener(heartbeatChunkListener)
            .listener(progressTrackingItemWriteListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }

    private fun createEmbeddingReader(
        accountId: Long,
        cutoffDate: OffsetDateTime,
        forceRefresh: Boolean
    ): ItemReader<ContentChunk> {
        log.debug("Creating EmailEmbeddingReader for account {} (cutoff={}, forceRefresh={})",
            accountId, cutoffDate, forceRefresh)
        return EmailEmbeddingReader(
            contentChunkRepository = contentChunkRepository,
            accountId = accountId,
            cutoffDate = cutoffDate,
            forceRefresh = forceRefresh,
            pageSize = 50
        )
    }

    private fun createEmbeddingWriter(): ItemWriter<ContentChunkDTO> {
        log.debug("Creating EmailEmbeddingWriter")
        return EmailEmbeddingWriter(contentChunkRepository = contentChunkRepository)
    }
}
