package com.oconeco.spring_search_tempo.base.service

/**
 * Service for generating vector embeddings from text.
 *
 * Provides embedding generation for semantic search using pgvector.
 * The default implementation uses Ollama with mxbai-embed-large (1024 dimensions).
 */
interface EmbeddingService {

    /**
     * Generate an embedding vector for the given text.
     *
     * @param text The text to embed
     * @return EmbeddingResult containing the float array and model info
     * @throws EmbeddingUnavailableException if the embedding service is not reachable
     */
    fun embed(text: String): EmbeddingResult

    /**
     * Check if the embedding service is available and responding.
     * Performs a lightweight probe (e.g., embedding a short test string).
     *
     * @return true if the service is reachable and functional
     */
    fun isAvailable(): Boolean

    /**
     * Get the name of the embedding model being used.
     */
    fun getModelName(): String

    /**
     * Check if the embedding model is running with GPU acceleration.
     * This helps warn users when embeddings will be slow due to CPU-only mode.
     *
     * @return GpuStatus indicating GPU availability and details
     */
    fun checkGpuStatus(): GpuStatus
}

/**
 * GPU status information for embedding service.
 */
data class GpuStatus(
    /** Whether GPU is being used for embeddings */
    val gpuAvailable: Boolean,
    /** GPU device name if available */
    val gpuDevice: String? = null,
    /** Mode description for UI display */
    val mode: String = if (gpuAvailable) "GPU" else "CPU",
    /** Warning message if GPU is not available */
    val warning: String? = if (!gpuAvailable) "Embeddings running in CPU mode - expect slow performance" else null
)

/**
 * Result of an embedding generation call.
 */
data class EmbeddingResult(
    val embedding: FloatArray,
    val modelName: String,
    val dimensions: Int = embedding.size
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingResult) return false
        return embedding.contentEquals(other.embedding) && modelName == other.modelName
    }

    override fun hashCode(): Int {
        var result = embedding.contentHashCode()
        result = 31 * result + modelName.hashCode()
        return result
    }
}

/**
 * Exception thrown when the embedding service is unavailable.
 */
class EmbeddingUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
