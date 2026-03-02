package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.OneDriveAccountService
import com.oconeco.spring_search_tempo.base.OneDriveItemService
import com.oconeco.spring_search_tempo.base.config.OneDriveConfiguration
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.model.OneDriveItemDTO
import com.oconeco.spring_search_tempo.base.service.OneDriveConnectionService
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
import com.oconeco.spring_search_tempo.batch.HeartbeatChunkListener
import com.oconeco.spring_search_tempo.batch.ProgressTrackingItemWriteListener
import com.oconeco.spring_search_tempo.batch.config.BatchTaskExecutorConfig.Companion.DEFAULT_THROTTLE_LIMIT
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
 * Builder for creating OneDrive sync batch jobs.
 *
 * Creates a 3-step job for each OneDrive account:
 * 1. Delta sync step - Metadata from Graph delta API
 * 2. Content download step - Download files + Tika text extraction
 * 3. Chunking step - Split bodyText into ContentChunks
 */
@Component
class OneDriveSyncJobBuilder(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val connectionService: OneDriveConnectionService,
    private val accountService: OneDriveAccountService,
    private val itemService: OneDriveItemService,
    private val chunkService: ContentChunkService,
    private val textExtractionService: TextExtractionService,
    private val config: OneDriveConfiguration,
    private val jobRunTrackingListener: OneDriveJobRunTrackingListener,
    private val heartbeatChunkListener: HeartbeatChunkListener,
    private val progressTrackingItemWriteListener: ProgressTrackingItemWriteListener<Any>,
    @Qualifier("stepTaskExecutor") private val stepTaskExecutor: TaskExecutor
) {
    companion object {
        private val log = LoggerFactory.getLogger(OneDriveSyncJobBuilder::class.java)
    }

    /**
     * Build a complete sync job for a OneDrive account.
     *
     * @param accountId The OneDrive account ID
     * @param forceFullSync If true, ignore delta token and do full enumeration
     * @return A configured Spring Batch Job
     */
    fun buildJob(accountId: Long, forceFullSync: Boolean = false): Job {
        val account = accountService.get(accountId)
        log.info("Building OneDrive sync job for account {} (forceFullSync={})",
            account.email, forceFullSync)

        val jobName = "oneDriveSync_$accountId"

        return JobBuilder(jobName, jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobRunTrackingListener)
            .start(buildDeltaSyncStep(accountId, forceFullSync))
            .next(buildContentDownloadStep(accountId))
            .next(buildChunkingStep(accountId))
            .build()
    }

    // ==================== PASS 1: DELTA SYNC ====================

    private fun buildDeltaSyncStep(accountId: Long, forceFullSync: Boolean): Step {
        log.info("Building delta sync step for account {}", accountId)

        val reader = OneDriveDeltaReader(
            connectionService = connectionService,
            accountService = accountService,
            accountId = accountId,
            forceFullSync = forceFullSync
        )

        return StepBuilder("oneDriveDeltaSync_$accountId", jobRepository)
            .chunk<GraphDriveItemWrapper, OneDriveItemDTO>(100, transactionManager)
            .reader(reader)
            .processor(OneDriveMetadataProcessor(accountId))
            .writer(OneDriveMetadataWriter(itemService))
            .listener(reader)  // Reader is a StepExecutionListener (saves delta token)
            .listener(heartbeatChunkListener)
            .listener(progressTrackingItemWriteListener)
            .build()
    }

    // ==================== PASS 2: CONTENT DOWNLOAD ====================

    private fun buildContentDownloadStep(accountId: Long): Step {
        log.info("Building content download step for account {}", accountId)

        val processor = OneDriveContentProcessor(
            connectionService = connectionService,
            accountService = accountService,
            textExtractionService = textExtractionService,
            config = config
        )

        return StepBuilder("oneDriveContentDownload_$accountId", jobRepository)
            .chunk<OneDriveItemDTO, OneDriveContentResult>(10, transactionManager)
            .reader(OneDriveContentReader(itemService, accountId))
            .processor(processor)
            .writer(OneDriveContentWriter(itemService))
            .listener(processor)  // Processor is a StepExecutionListener (creates temp dir)
            .listener(heartbeatChunkListener)
            .listener(progressTrackingItemWriteListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }

    // ==================== PASS 3: CHUNKING ====================

    private fun buildChunkingStep(accountId: Long): Step {
        log.info("Building chunking step for OneDrive account {}", accountId)

        return StepBuilder("oneDriveChunking_$accountId", jobRepository)
            .chunk<OneDriveItemDTO, List<ContentChunkDTO>>(10, transactionManager)
            .reader(OneDriveChunkReader(itemService, accountId))
            .processor(OneDriveChunkProcessor())
            .writer(OneDriveChunkWriter(chunkService, itemService))
            .listener(heartbeatChunkListener)
            .listener(progressTrackingItemWriteListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }
}
