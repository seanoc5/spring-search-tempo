package com.oconeco.spring_search_tempo.base.service

import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Embedding service implementation using Spring AI's Ollama integration.
 *
 * Wraps the auto-configured EmbeddingModel bean provided by spring-ai-starter-model-ollama.
 * Default model: mxbai-embed-large (1024 dimensions).
 */
@Service
class OllamaEmbeddingService(
    private val embeddingModel: EmbeddingModel,
    @Value("\${app.embedding.model-name:mxbai-embed-large}")
    private val modelName: String
) : EmbeddingService {

    companion object {
        private val log = LoggerFactory.getLogger(OllamaEmbeddingService::class.java)
    }

    override fun embed(text: String): EmbeddingResult {
        if (text.isBlank()) {
            throw IllegalArgumentException("Cannot embed blank text")
        }

        try {
            val floatArray = embeddingModel.embed(text)

            log.debug("Generated embedding with {} dimensions for text ({} chars)",
                floatArray.size, text.length)

            return EmbeddingResult(
                embedding = floatArray,
                modelName = modelName
            )
        } catch (e: EmbeddingUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw EmbeddingUnavailableException(
                "Failed to generate embedding: ${e.message}", e
            )
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            embeddingModel.embed("test")
            true
        } catch (e: Exception) {
            log.debug("Embedding service unavailable: {}", e.message)
            false
        }
    }

    override fun getModelName(): String = modelName
}
