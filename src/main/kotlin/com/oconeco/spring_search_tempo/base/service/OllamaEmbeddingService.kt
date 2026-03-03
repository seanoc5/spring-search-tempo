package com.oconeco.spring_search_tempo.base.service

import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject

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
    private val modelName: String,
    @Value("\${spring.ai.ollama.base-url:http://localhost:11434}")
    private val ollamaBaseUrl: String
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

    // Cached GPU status (checked once per service lifetime)
    private var cachedGpuStatus: GpuStatus? = null

    override fun checkGpuStatus(): GpuStatus {
        // Return cached status if available
        cachedGpuStatus?.let { return it }

        val status = try {
            // Try to query Ollama's /api/ps endpoint to see running models
            val restTemplate = RestTemplate()
            val response = restTemplate.getForObject<Map<String, Any>>("$ollamaBaseUrl/api/ps")

            // Check if any model is using GPU
            val models = response?.get("models") as? List<*>
            val hasGpu = models?.any { model ->
                val modelMap = model as? Map<*, *>
                val details = modelMap?.get("details") as? Map<*, *>
                // Check for GPU-related fields
                val gpuLayers = details?.get("gpu_layers")
                gpuLayers != null && gpuLayers.toString().toIntOrNull()?.let { it > 0 } == true
            } ?: false

            if (hasGpu) {
                GpuStatus(
                    gpuAvailable = true,
                    mode = "GPU",
                    gpuDevice = "Ollama GPU acceleration"
                )
            } else {
                // Try alternative detection via nvidia-smi or similar
                detectGpuFromSystem()
            }
        } catch (e: Exception) {
            log.debug("Could not query Ollama for GPU status: {}", e.message)
            // Fall back to system detection
            detectGpuFromSystem()
        }

        cachedGpuStatus = status

        if (!status.gpuAvailable) {
            log.warn("GPU not detected for embedding model. CPU mode will be significantly slower.")
        } else {
            log.info("GPU detected for embeddings: {}", status.gpuDevice ?: "available")
        }

        return status
    }

    /**
     * Detect GPU availability from system (fallback method).
     */
    private fun detectGpuFromSystem(): GpuStatus {
        return try {
            // Try nvidia-smi on Linux/macOS
            val process = ProcessBuilder("nvidia-smi", "--query-gpu=name", "--format=csv,noheader")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotBlank()) {
                GpuStatus(
                    gpuAvailable = true,
                    gpuDevice = output.lines().firstOrNull() ?: "NVIDIA GPU",
                    mode = "GPU"
                )
            } else {
                GpuStatus(gpuAvailable = false)
            }
        } catch (e: Exception) {
            // nvidia-smi not available or failed
            log.debug("nvidia-smi not available: {}", e.message)
            GpuStatus(gpuAvailable = false)
        }
    }
}
