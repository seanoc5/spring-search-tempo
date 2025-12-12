package com.oconeco.spring_search_tempo.batch.nlp

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
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
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.data.RepositoryItemReader
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
 *
 * IMPORTANT: Only chunks from files/emails with analysisStatus of ANALYZE or SEMANTIC
 * are processed. Files with lower analysis levels (SKIP, LOCATE, INDEX) are not
 * processed for NLP even if they have content chunks.
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

        /**
         * Analysis status levels that qualify for NLP processing.
         * ANALYZE: Full NLP analysis (NER, POS, sentiment)
         * SEMANTIC: ANALYZE + future vector embeddings
         */
        val NLP_ELIGIBLE_STATUSES = listOf(AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC)
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
     * Reads entities, maps to DTOs for processing, then writes back to entities.
     */
    @Bean
    fun nlpProcessingStep(): Step {
        return StepBuilder("nlpProcessingStep", jobRepository)
            .chunk<ContentChunk, ContentChunkDTO>(CHUNK_SIZE, transactionManager)
            .reader(nlpChunkReader())
            .processor(nlpChunkProcessor())
            .writer(nlpChunkWriter())
            .build()
    }

    /**
     * Reader for content chunks that need NLP processing.
     *
     * Only reads ContentChunk entities where:
     * - nlpProcessedAt is null (not yet processed)
     * - text is not null (has content to analyze)
     * - Parent file/email has analysisStatus of ANALYZE or SEMANTIC
     *
     * This ensures NLP processing only runs on content that has been explicitly
     * marked for deep analysis, not on files that are only being indexed.
     */
    @Bean
    fun nlpChunkReader(): RepositoryItemReader<ContentChunk> {
        val reader = RepositoryItemReader<ContentChunk>()
        reader.setRepository(contentChunksRepository)
        reader.setMethodName("findChunksForNlpProcessing")
        reader.setArguments(listOf(NLP_ELIGIBLE_STATUSES))
        reader.setPageSize(CHUNK_SIZE)
        // Note: Sort is handled in the @Query itself (ORDER BY c.id ASC)
        // but RepositoryItemReader requires a sort map for pagination
        reader.setSort(mapOf("id" to Sort.Direction.ASC))
        return reader
    }

    /**
     * Processor for NLP analysis.
     * Maps entity to DTO, performs NLP, returns enriched DTO.
     */
    @Bean
    fun nlpChunkProcessor(): ItemProcessor<ContentChunk, ContentChunkDTO> {
        return ItemProcessor { entity ->
            // Map entity to DTO
            val dto = contentChunksMapper.toDto(entity)
            // Process with NLP (delegate to the actual processor)
            val nlpProcessor = NLPChunkProcessor(nlpService, objectMapper, contentChunksMapper)
            nlpProcessor.process(dto)
        }
    }

    /**
     * Writer for processed chunks.
     * Updates entities with NLP results from DTOs.
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
