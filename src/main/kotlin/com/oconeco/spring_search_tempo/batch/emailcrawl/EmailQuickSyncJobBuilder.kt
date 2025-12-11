package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.EmailFolderService
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.service.EmailTextExtractionService
import com.oconeco.spring_search_tempo.base.service.ImapConnectionService
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


/**
 * Builder for creating email quick sync batch jobs.
 *
 * Creates a job for each email account with:
 * 1. Sync steps for configured folders (INBOX, Sent)
 * 2. Chunking step to split email body text into ContentChunks
 */
@Component
class EmailQuickSyncJobBuilder(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val imapConnectionService: ImapConnectionService,
    private val emailMessageService: EmailMessageService,
    private val emailFolderService: EmailFolderService,
    private val emailTextExtractionService: EmailTextExtractionService,
    private val chunkService: ContentChunkService,
    private val objectMapper: ObjectMapper
) {
    companion object {
        private val log = LoggerFactory.getLogger(EmailQuickSyncJobBuilder::class.java)
    }

    /**
     * Build a complete batch job for an email account.
     *
     * @param account The email account to sync
     * @param folders The folder names to sync (e.g., ["INBOX", "Sent"])
     * @return A configured Spring Batch Job
     */
    fun buildJob(account: EmailAccountDTO, folders: List<String>): Job {
        log.info(
            "Building email quick sync job for account: {} ({}) with {} folders",
            account.email,
            account.label,
            folders.size
        )

        val jobName = "emailQuickSync_${account.id}"

        val jobBuilder = JobBuilder(jobName, jobRepository)
            .incrementer(RunIdIncrementer())

        // Build sync steps for each folder
        var currentFlow = jobBuilder.start(buildSyncStep(account, folders.first()))

        folders.drop(1).forEach { folder ->
            currentFlow = currentFlow.next(buildSyncStep(account, folder))
        }

        // Add chunking step at the end
        return currentFlow
            .next(buildChunkingStep(account))
            .build()
    }

    /**
     * Build a sync step for a specific folder.
     */
    private fun buildSyncStep(account: EmailAccountDTO, folderName: String): Step {
        log.info("Building sync step for {}/{}", account.email, folderName)

        val reader = createSyncReader(account, folderName)
        val writer = createSyncWriter(account.id!!, folderName)

        return StepBuilder("emailSync_${account.id}_$folderName", jobRepository)
            .chunk<ImapMessageWrapper, EmailMessageDTO>(50, transactionManager)
            .reader(reader)
            .processor(createSyncProcessor())
            .writer(writer)
            .listener(writer)  // Writer is also a step listener for sync state updates
            .build()
    }

    /**
     * Create a sync reader for a folder.
     */
    private fun createSyncReader(account: EmailAccountDTO, folderName: String): ItemReader<ImapMessageWrapper> {
        log.debug("Creating EmailQuickSyncReader for {}/{}", account.email, folderName)
        return EmailQuickSyncReader(
            account = account,
            folderName = folderName,
            imapConnectionService = imapConnectionService,
            emailFolderService = emailFolderService
        )
    }

    /**
     * Create a sync processor.
     */
    private fun createSyncProcessor(): ItemProcessor<ImapMessageWrapper, EmailMessageDTO> {
        log.debug("Creating EmailQuickSyncProcessor")
        return EmailQuickSyncProcessor(
            emailMessageService = emailMessageService,
            emailTextExtractionService = emailTextExtractionService,
            objectMapper = objectMapper
        )
    }

    /**
     * Create a sync writer.
     */
    private fun createSyncWriter(accountId: Long, folderName: String): EmailQuickSyncWriter {
        log.debug("Creating EmailQuickSyncWriter for account {} folder {}", accountId, folderName)
        return EmailQuickSyncWriter(
            emailMessageService = emailMessageService,
            emailFolderService = emailFolderService,
            accountId = accountId,
            folderName = folderName
        )
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
}
