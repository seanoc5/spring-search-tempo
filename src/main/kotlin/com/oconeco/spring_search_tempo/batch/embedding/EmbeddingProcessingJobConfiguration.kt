package com.oconeco.spring_search_tempo.batch.embedding

import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.service.ContentChunkMapper
import com.oconeco.spring_search_tempo.base.service.EmbeddingService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.data.RepositoryItemReader
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Sort
import org.springframework.transaction.PlatformTransactionManager
import java.time.OffsetDateTime

/**
 * Batch job configuration for embedding generation across ALL content chunk types
 * (email, file, onedrive, bookmarks, etc.).
 *
 * This is a standalone job that can be triggered via REST API or programmatically.
 * For email-specific embedding within the email pipeline, see EmailQuickSyncJobBuilder.
 */
@Configuration
class EmbeddingProcessingJobConfiguration(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val contentChunkRepository: ContentChunkRepository,
    private val contentChunkMapper: ContentChunkMapper,
    private val embeddingService: EmbeddingService,
    @Value("\${app.embedding.batch-size:10}")
    private val batchSize: Int,
    @Value("\${app.embedding.max-text-length:8192}")
    private val maxTextLength: Int
) {

    companion object {
        private val log = LoggerFactory.getLogger(EmbeddingProcessingJobConfiguration::class.java)
        private val EMBED_ELIGIBLE_STATUSES = listOf(AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC)
    }

    @Bean
    fun embeddingProcessingJob(): Job {
        return JobBuilder("embeddingProcessingJob", jobRepository)
            .start(embeddingProcessingStep())
            .build()
    }

    @Bean
    fun embeddingProcessingStep(): Step {
        return StepBuilder("embeddingProcessingStep", jobRepository)
            .chunk<ContentChunk, ContentChunkDTO>(batchSize, transactionManager)
            .reader(embeddingChunkReader())
            .processor(embeddingChunkProcessor())
            .writer(embeddingChunkWriter())
            .build()
    }

    /**
     * Reader for content chunks that need embedding.
     * Reads all eligible chunks that don't yet have embeddings.
     */
    @Bean
    fun embeddingChunkReader(): RepositoryItemReader<ContentChunk> {
        val reader = RepositoryItemReader<ContentChunk>()
        reader.setRepository(contentChunkRepository)
        reader.setMethodName("findChunksForEmbedding")
        reader.setArguments(listOf(false, EMBED_ELIGIBLE_STATUSES))
        reader.setPageSize(batchSize)
        reader.setSort(mapOf("id" to Sort.Direction.ASC))
        return reader
    }

    /**
     * Processor that generates embeddings via EmbeddingService.
     */
    @Bean
    fun embeddingChunkProcessor(): ItemProcessor<ContentChunk, ContentChunkDTO> {
        val processor = EmbeddingChunkProcessor(embeddingService, maxTextLength)
        return ItemProcessor { entity ->
            val dto = contentChunkMapper.toDto(entity)
            processor.process(dto)
        }
    }

    /**
     * Writer that persists embeddings via native SQL update.
     */
    @Bean
    fun embeddingChunkWriter(): ItemWriter<ContentChunkDTO> {
        return ItemWriter { chunks ->
            chunks.forEach { dto ->
                val id = dto.id ?: return@forEach
                val embedding = dto.embedding ?: return@forEach
                val generatedAt = dto.embeddingGeneratedAt ?: OffsetDateTime.now()
                val modelName = dto.embeddingModel ?: "unknown"

                val embeddingStr = embedding.joinToString(",", "[", "]")
                contentChunkRepository.updateEmbedding(id, embeddingStr, generatedAt, modelName)
                log.debug("Saved embedding for chunk {} ({} dims)", id, embedding.size)
            }
        }
    }
}
