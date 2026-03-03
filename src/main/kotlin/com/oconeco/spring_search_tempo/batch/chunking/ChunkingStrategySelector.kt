package com.oconeco.spring_search_tempo.batch.chunking

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Selects the appropriate ChunkingStrategy based on content type and file metadata.
 *
 * Strategy selection order:
 * 1. Check content type against each strategy (sorted by priority)
 * 2. Fall back to file extension-based detection
 * 3. Use SentenceChunker as default
 */
@Service
class ChunkingStrategySelector(
    strategies: List<ChunkingStrategy>
) {
    private val log = LoggerFactory.getLogger(ChunkingStrategySelector::class.java)

    // Sort strategies by priority (highest first)
    private val sortedStrategies = strategies.sortedByDescending { it.priority }

    // Keep reference to sentence chunker as the default
    private val defaultStrategy: ChunkingStrategy = strategies
        .filterIsInstance<SentenceChunker>()
        .firstOrNull()
        ?: throw IllegalStateException("SentenceChunker must be available as default strategy")

    /**
     * Selects the best chunking strategy for the given metadata.
     *
     * @param metadata Information about the content to chunk
     * @return The selected chunking strategy
     */
    fun selectStrategy(metadata: ChunkMetadata): ChunkingStrategy {
        // First, try content type matching (highest priority first)
        for (strategy in sortedStrategies) {
            if (strategy.supports(metadata.contentType)) {
                if (strategy != defaultStrategy) {
                    log.debug(
                        "Selected {} strategy for content type: {}",
                        strategy.name,
                        metadata.contentType
                    )
                }
                return strategy
            }
        }

        // Fall back to extension-based detection
        val extension = metadata.fileExtension
        if (extension != null) {
            for (strategy in sortedStrategies) {
                val supportsExtension = when (strategy) {
                    is CodeChunker -> strategy.supportsByExtension(extension)
                    is ParagraphChunker -> strategy.supportsByExtension(extension)
                    else -> false
                }
                if (supportsExtension) {
                    log.debug(
                        "Selected {} strategy for extension: {}",
                        strategy.name,
                        extension
                    )
                    return strategy
                }
            }
        }

        // Default to sentence chunker
        return defaultStrategy
    }

    /**
     * Returns all available strategy names (for debugging/admin UI).
     */
    fun availableStrategies(): List<String> {
        return sortedStrategies.map { it.name }
    }
}
