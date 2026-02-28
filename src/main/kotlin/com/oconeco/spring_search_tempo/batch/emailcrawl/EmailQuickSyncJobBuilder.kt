package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.EmailAccountService
import com.oconeco.spring_search_tempo.base.EmailCategorizationService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.service.EmailTextExtractionService
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
import com.oconeco.spring_search_tempo.batch.HeartbeatChunkListener
import com.oconeco.spring_search_tempo.batch.ProgressTrackingItemWriteListener
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.integration.async.AsyncItemProcessor
import org.springframework.batch.integration.async.AsyncItemWriter
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemReader
import org.springframework.batch.item.ItemWriter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import java.util.concurrent.Future


/**
 * Builder for creating email sync batch jobs with multi-pass architecture.
 *
 * Creates a job for each email account with:
 * 1. Pass 1: Header sync steps for each folder (fast - headers only)
 * 2. Pass 2: Body enrichment step (slower - fetches bodies, can be parallelized)
 * 3. Chunking step to split email body text into ContentChunks
 * 4. Categorization step to classify emails by type
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
    @Qualifier("stepTaskExecutor") private val stepTaskExecutor: TaskExecutor,
    @Qualifier("asyncItemExecutor") private val asyncItemExecutor: TaskExecutor
) {
    companion object {
        private val log = LoggerFactory.getLogger(EmailQuickSyncJobBuilder::class.java)
    }

    /**
     * Build a complete batch job for an email account.
     *
     * Job structure (two-pass architecture):
     * 1. Pass 1: Header sync for each folder (fast - only envelope data)
     * 2. Pass 2: Body enrichment (slower - fetches full message bodies)
     * 3. Chunking step (splits body text for NLP)
     *
     * @param account The email account to sync
     * @param folders The folder names to sync (e.g., ["INBOX", "Sent"])
     * @param forceFullSync If true, ignore lastSyncUid and fetch all messages
     * @param parallelConfig Configuration for parallel processing (default: serial)
     * @return A configured Spring Batch Job
     */
    fun buildJob(
        account: EmailAccountDTO,
        folders: List<String>,
        forceFullSync: Boolean = false,
        parallelConfig: ParallelizationConfig = ParallelizationConfig()
    ): Job {
        log.info(
            "Building email {} job for account: {} ({}) with {} folders (two-pass, {})",
            if (forceFullSync) "FULL sync" else "quick sync",
            account.email,
            account.label,
            folders.size,
            parallelConfig.modeName
        )

        val jobName = "emailQuickSync_${account.id}"

        val jobBuilder = JobBuilder(jobName, jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(emailJobRunTrackingListener)  // Track job run with heartbeat

        // Pass 1: Build header sync steps for each folder (fast)
        var currentFlow = jobBuilder.start(buildHeaderSyncStep(account, folders.first(), forceFullSync))

        folders.drop(1).forEach { folder ->
            currentFlow = currentFlow.next(buildHeaderSyncStep(account, folder, forceFullSync))
        }

        // Pass 2: Body enrichment step (slower, processes all HEADERS_ONLY messages)
        // Uses parallelConfig for optional parallel processing
        currentFlow = currentFlow.next(buildBodyEnrichmentStep(account, parallelConfig))

        // Pass 3: Chunking step
        currentFlow = currentFlow.next(buildChunkingStep(account))

        // Pass 4: Categorization step
        return currentFlow
            .next(buildCategorizationStep(account))
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

    /**
     * Build the chunking step that splits email body text into ContentChunks.
     */
    private fun buildChunkingStep(account: EmailAccountDTO): Step {
        log.info("Building chunking step for email account: {}", account.email)

        return StepBuilder("emailChunking_${account.id}", jobRepository)
            .chunk<EmailMessageDTO, List<ContentChunkDTO>>(10, transactionManager)
            .reader(createChunkReader(account.id!!))
            .processor(createChunkProcessor())
            .writer(createChunkWriter())
            .listener(heartbeatChunkListener)  // Update heartbeat after each chunk
            .listener(progressTrackingItemWriteListener)  // Track progress for UI
            .build()
    }

    /**
     * Create a chunk reader that reads email messages with bodyText that need chunking.
     */
    private fun createChunkReader(accountId: Long): ItemReader<EmailMessageDTO> {
        log.debug("Creating EmailChunkReader for account {}", accountId)
        return EmailChunkReader(
            emailMessageService = emailMessageService,
            accountId = accountId,
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
    private fun createChunkWriter(): ItemWriter<List<ContentChunkDTO>> {
        log.debug("Creating EmailChunkWriter")
        return EmailChunkWriter(chunkService = chunkService)
    }

    // ==================== PASS 4: CATEGORIZATION ====================

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
}
