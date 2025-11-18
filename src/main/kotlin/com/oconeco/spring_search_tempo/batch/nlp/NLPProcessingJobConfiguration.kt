package com.oconeco.spring_search_tempo.batch.nlp

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.service.ContentChunkMapper
import com.oconeco.spring_search_tempo.base.service.NLPService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemReader
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.data.RepositoryItemReader
import org.springframework.batch.item.data.RepositoryItemWriter
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder
import org.springframework.batch.item.data.builder.RepositoryItemWriterBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Sort
import org.springframework.transaction.PlatformTransactionManager

/**
 * Batch job configuration for NLP processing of content chunks.
 *
 * This job processes content chunks that have been created by the file crawl job
 * and performs NLP analysis including:
 * - Named Entity Recognition (NER)
 * - Part-of-Speech (POS) tagging
 * - Sentiment analysis
 *
 * Processing is done in chunks to handle large datasets efficiently.
 */
@Configuration
class NLPProcessingJobConfiguration(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val contentChunksRepository: ContentChunkRepository,
    private val contentChunksMapper: ContentChunkMapper,
    private val nlpService: NLPService,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private val log = LoggerFactory.getLogger(NLPProcessingJobConfiguration::class.java)
        private const val CHUNK_SIZE = 10  // Process 10 chunks at a time
    }

    /**
     * Main NLP processing job.
     */
    @Bean
    fun nlpProcessingJob(): Job {
        return JobBuilder("nlpProcessingJob", jobRepository)
            .start(nlpProcessingStep())
            .build()
    }

    /**
     * Step for processing chunks with NLP.
     */
    @Bean
    fun nlpProcessingStep(): Step {
        return StepBuilder("nlpProcessingStep", jobRepository)
            .chunk<ContentChunkDTO, ContentChunkDTO>(CHUNK_SIZE, transactionManager)
            .reader(nlpChunkReader())
            .processor(nlpChunkProcessor())
            .writer(nlpChunkWriter())
            .build()
    }

    /**
     * Reader for content chunks that need NLP processing.
     * Reads chunks where nlpProcessedAt is null and text is not null.
     */
    @Bean
    fun nlpChunkReader(): RepositoryItemReader<ContentChunkDTO> {
        val reader = RepositoryItemReader<ContentChunkDTO>()
        reader.setRepository(contentChunksRepository)
        reader.setMethodName("findByNlpProcessedAtIsNullAndTextIsNotNull")
        reader.setPageSize(CHUNK_SIZE)
        reader.setSort(mapOf("id" to Sort.Direction.ASC))
        return reader
    }

    /**
     * Processor for NLP analysis.
     */
    @Bean
    fun nlpChunkProcessor(): ItemProcessor<ContentChunkDTO, ContentChunkDTO> {
        return NLPChunkProcessor(nlpService, objectMapper, contentChunksMapper)
    }

    /**
     * Writer for processed chunks.
     * Converts DTOs back to entities and saves them.
     */
    @Bean
    fun nlpChunkWriter(): ItemWriter<ContentChunkDTO> {
        return ItemWriter { chunks ->
            chunks.forEach { dto ->
                // Find the entity and update it
                dto.id?.let { id ->
                    contentChunksRepository.findById(id).ifPresent { entity ->
                        // Update NLP fields
                        entity.namedEntities = dto.namedEntities
                        entity.tokenAnnotations = dto.tokenAnnotations
                        entity.nouns = dto.nouns
                        entity.verbs = dto.verbs
                        entity.sentiment = dto.sentiment
                        entity.sentimentScore = dto.sentimentScore
                        entity.nlpProcessedAt = dto.nlpProcessedAt

                        contentChunksRepository.save(entity)
                        log.debug("Saved NLP results for chunk {}", id)
                    }
                }
            }
        }
    }
}
