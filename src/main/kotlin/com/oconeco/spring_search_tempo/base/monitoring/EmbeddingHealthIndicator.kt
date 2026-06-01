package com.oconeco.spring_search_tempo.base.monitoring

import com.oconeco.spring_search_tempo.base.service.SemanticSearchService
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Health indicator for the embedding/semantic search service.
 *
 * Reports UP when Ollama embedding service is available,
 * DOWN when unavailable (with details about the issue).
 */
@Component("embeddingService")
class EmbeddingHealthIndicator(
    private val semanticSearchService: SemanticSearchService
) : HealthIndicator {

    override fun health(): Health {
        return try {
            val stats = semanticSearchService.getStats()

            if (stats.embeddingServiceAvailable) {
                val totalChunks = stats.chunksWithEmbedding + stats.chunksPendingEmbedding
                Health.up()
                    .withDetail("model", stats.modelName ?: "unknown")
                    .withDetail("gpuMode", stats.gpuMode ?: "unknown")
                    .withDetail("chunksWithEmbedding", stats.chunksWithEmbedding)
                    .withDetail("chunksPending", stats.chunksPendingEmbedding)
                    .withDetail("coveragePercent",
                        if (totalChunks > 0)
                            "%.1f%%".format(stats.chunksWithEmbedding * 100.0 / totalChunks)
                        else "N/A")
                    .build()
            } else {
                Health.down()
                    .withDetail("reason", "Ollama embedding service unavailable")
                    .withDetail("hint", "Start Ollama with: ollama serve")
                    .build()
            }
        } catch (e: Exception) {
            Health.down()
                .withDetail("error", e.message ?: "Unknown error")
                .build()
        }
    }
}
