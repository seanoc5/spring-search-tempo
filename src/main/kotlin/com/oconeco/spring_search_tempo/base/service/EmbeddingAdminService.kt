package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import org.springframework.stereotype.Service

@Service
class EmbeddingAdminService(
    private val embeddingService: EmbeddingService,
    private val contentChunkRepository: ContentChunkRepository
) {
    fun getEmbeddingStatus(): EmbeddingAdminStatus =
        EmbeddingAdminStatus(
            available = embeddingService.isAvailable(),
            modelName = embeddingService.getModelName(),
            chunksProcessed = contentChunkRepository.countByEmbeddingGeneratedAtIsNotNull(),
            chunksPending = contentChunkRepository.countEmbeddingPending()
        )
}

data class EmbeddingAdminStatus(
    val available: Boolean,
    val modelName: String,
    val chunksProcessed: Long,
    val chunksPending: Long
)
