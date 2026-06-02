package com.oconeco.spring_search_tempo.base.model

import java.time.Instant

/**
 * Aggregate view model for the NLP coverage admin page (`GET /nlp/status`).
 * Built once per page render by the controller — keep it cheap to compute.
 */
data class NLPStatusViewDTO(
    val totalChunks: Long,
    val chunksAtIndexLevel: Long,
    val chunksAtAnalyzeLevel: Long,
    val nlpProcessedCount: Long,
    val coveragePercent: Int,
    val nlpPendingAtAnalyzeLevel: Long,
    val autoTriggerEnabled: Boolean,
    val lastJob: NLPJobView?,
    val recentJobs: List<NLPJobView>
)

/**
 * Compact projection of a Spring Batch `JobExecution` for the NLP page.
 * We pull just the fields the template uses — not the full JobExecution graph.
 */
data class NLPJobView(
    val executionId: Long?,
    val status: String,
    val exitCode: String?,
    val startTime: Instant?,
    val endTime: Instant?,
    val durationSeconds: Long?,
    val readCount: Long,
    val writeCount: Long,
    val skipCount: Long,
    val triggeredBy: String?
)
