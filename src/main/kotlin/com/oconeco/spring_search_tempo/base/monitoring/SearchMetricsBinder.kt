package com.oconeco.spring_search_tempo.base.monitoring

import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.service.SemanticSearchService
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Exposes search-related metrics for Prometheus/Grafana.
 *
 * Metrics:
 * - tempo.search.files.total - Total indexed files
 * - tempo.search.chunks.total - Total content chunks
 * - tempo.search.chunks.with_embedding - Chunks with vector embeddings
 * - tempo.search.chunks.with_nlp - Chunks with NLP processing
 * - tempo.search.embedding.available - Whether embedding service is available (1 or 0)
 */
@Component
class SearchMetricsBinder(
    meterRegistry: MeterRegistry,
    private val fsFileRepository: FSFileRepository,
    private val contentChunkRepository: ContentChunkRepository,
    private val semanticSearchService: SemanticSearchService
) {
    companion object {
        private val log = LoggerFactory.getLogger(SearchMetricsBinder::class.java)
    }

    init {
        // File metrics
        Gauge.builder("tempo.search.files.total") {
            safeCount { fsFileRepository.count() }
        }
            .description("Total indexed files")
            .register(meterRegistry)

        // Chunk metrics
        Gauge.builder("tempo.search.chunks.total") {
            safeCount { contentChunkRepository.count() }
        }
            .description("Total content chunks")
            .register(meterRegistry)

        Gauge.builder("tempo.search.chunks.with_embedding") {
            safeCount { contentChunkRepository.countByEmbeddingGeneratedAtIsNotNull() }
        }
            .description("Content chunks with vector embeddings")
            .register(meterRegistry)

        Gauge.builder("tempo.search.chunks.with_nlp") {
            safeCount { contentChunkRepository.countByNlpProcessedAtIsNotNull() }
        }
            .description("Content chunks with NLP processing")
            .register(meterRegistry)

        Gauge.builder("tempo.search.chunks.with_entities") {
            safeCount { contentChunkRepository.countByNamedEntitiesIsNotNull() }
        }
            .description("Content chunks with named entities")
            .register(meterRegistry)

        // Embedding service availability
        Gauge.builder("tempo.search.embedding.available") {
            if (semanticSearchService.isAvailable()) 1.0 else 0.0
        }
            .description("Embedding service availability (1=available, 0=unavailable)")
            .register(meterRegistry)
    }

    private fun safeCount(counter: () -> Long): Double = try {
        counter().toDouble()
    } catch (e: Exception) {
        log.debug("Failed to read search metric: {}", e.message)
        0.0
    }
}
