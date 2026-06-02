package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.service.NLPStatusViewService
import com.oconeco.spring_search_tempo.batch.nlp.NLPJobLauncher
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST API for NLP processing operations.
 */
@RestController
@RequestMapping("/api/nlp")
class NLPResource(
    private val nlpJobLauncher: NLPJobLauncher,
    private val nlpStatusViewService: NLPStatusViewService,
    @Value("\${app.nlp.auto-trigger:true}")
    private val autoTriggerEnabled: Boolean
) {
    companion object {
        private val log = LoggerFactory.getLogger(NLPResource::class.java)
    }

    /**
     * Trigger NLP processing job manually.
     *
     * POST /api/nlp/process
     *
     * @return Job execution status
     */
    @PostMapping("/process")
    fun triggerNLPProcessing(): ResponseEntity<NLPJobResponse> {
        log.info("REST API request to trigger NLP processing")

        return try {
            val execution = nlpJobLauncher.launchNLPJob(triggeredBy = "api")

            val response = NLPJobResponse(
                executionId = execution.id,
                jobName = execution.jobInstance.jobName,
                status = execution.status.name,
                message = "NLP processing job started successfully"
            )

            if (execution.status == BatchStatus.COMPLETED) {
                ResponseEntity.ok(response)
            } else if (execution.status == BatchStatus.STARTED || execution.status == BatchStatus.STARTING) {
                ResponseEntity.accepted().body(response)
            } else {
                ResponseEntity.ok(response)
            }
        } catch (e: Exception) {
            log.error("Failed to trigger NLP processing via API", e)
            ResponseEntity.internalServerError().body(
                NLPJobResponse(
                    executionId = null,
                    jobName = "nlpProcessingJob",
                    status = "FAILED",
                    message = "Failed to start NLP processing: ${e.message}"
                )
            )
        }
    }

    /**
     * Get NLP processing status/info.
     *
     * GET /api/nlp/status
     */
    @GetMapping("/status")
    fun getNLPStatus(): ResponseEntity<NLPStatusResponse> {
        val status = nlpStatusViewService.loadStatus()
        return ResponseEntity.ok(
            NLPStatusResponse(
                enabled = true,
                autoTriggerEnabled = autoTriggerEnabled,
                totalChunks = status.totalChunks,
                nlpProcessedCount = status.nlpProcessedCount,
                nlpPendingAtAnalyzeLevel = status.nlpPendingAtAnalyzeLevel,
                coveragePercent = status.coveragePercent,
                message = "NLP processing is available. Use POST /api/nlp/process to trigger manually."
            )
        )
    }
}

/**
 * Response DTO for NLP job operations.
 */
data class NLPJobResponse(
    val executionId: Long?,
    val jobName: String,
    val status: String,
    val message: String
)

/**
 * Response DTO for NLP status.
 */
data class NLPStatusResponse(
    val enabled: Boolean,
    val autoTriggerEnabled: Boolean,
    val totalChunks: Long = 0,
    val nlpProcessedCount: Long = 0,
    val nlpPendingAtAnalyzeLevel: Long = 0,
    val coveragePercent: Int = 0,
    val message: String
)
