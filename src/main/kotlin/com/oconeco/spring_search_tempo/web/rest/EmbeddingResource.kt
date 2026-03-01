package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.service.EmbeddingService
import com.oconeco.spring_search_tempo.batch.embedding.EmbeddingJobLauncher
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST API for embedding generation operations.
 */
@RestController
@RequestMapping("/api/embedding")
class EmbeddingResource(
    private val embeddingJobLauncher: EmbeddingJobLauncher,
    private val embeddingService: EmbeddingService,
    private val contentChunkRepository: ContentChunkRepository
) {
    companion object {
        private val log = LoggerFactory.getLogger(EmbeddingResource::class.java)
    }

    /**
     * Trigger the standalone embedding processing job.
     *
     * POST /api/embedding/process
     */
    @PostMapping("/process")
    fun triggerEmbeddingProcessing(): ResponseEntity<EmbeddingJobResponse> {
        log.info("REST API request to trigger embedding processing")

        return try {
            val execution = embeddingJobLauncher.launchEmbeddingJob(triggeredBy = "api")

            val response = EmbeddingJobResponse(
                executionId = execution.id,
                jobName = execution.jobInstance.jobName,
                status = execution.status.name,
                message = "Embedding processing job started successfully"
            )

            if (execution.status == BatchStatus.COMPLETED) {
                ResponseEntity.ok(response)
            } else if (execution.status == BatchStatus.STARTED || execution.status == BatchStatus.STARTING) {
                ResponseEntity.accepted().body(response)
            } else {
                ResponseEntity.ok(response)
            }
        } catch (e: Exception) {
            log.error("Failed to trigger embedding processing via API", e)
            ResponseEntity.internalServerError().body(
                EmbeddingJobResponse(
                    executionId = null,
                    jobName = "embeddingProcessingJob",
                    status = "FAILED",
                    message = "Failed to start embedding processing: ${e.message}"
                )
            )
        }
    }

    /**
     * Get embedding processing status and statistics.
     *
     * GET /api/embedding/status
     */
    @GetMapping("/status")
    fun getEmbeddingStatus(): ResponseEntity<EmbeddingStatusResponse> {
        val available = embeddingService.isAvailable()
        val modelName = embeddingService.getModelName()
        val processed = contentChunkRepository.countByEmbeddingGeneratedAtIsNotNull()
        val pending = contentChunkRepository.countEmbeddingPending()

        return ResponseEntity.ok(
            EmbeddingStatusResponse(
                available = available,
                modelName = modelName,
                chunksProcessed = processed,
                chunksPending = pending
            )
        )
    }
}

data class EmbeddingJobResponse(
    val executionId: Long?,
    val jobName: String,
    val status: String,
    val message: String
)

data class EmbeddingStatusResponse(
    val available: Boolean,
    val modelName: String,
    val chunksProcessed: Long,
    val chunksPending: Long
)
