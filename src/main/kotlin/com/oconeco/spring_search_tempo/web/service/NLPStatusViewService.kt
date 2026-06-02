package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.model.NLPJobView
import com.oconeco.spring_search_tempo.base.model.NLPStatusViewDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.ZoneId

/**
 * Aggregates the data shown on the NLP coverage admin page (`GET /nlp/status`).
 *
 * Reads are intentionally cheap:
 *  - Chunk counts come from `countDashboardAggregates` (already used by the home
 *    dashboard) plus two filtered counts against parent `analysisStatus`.
 *  - Job history is bounded — we look at the most recent N instances of
 *    `nlpProcessingJob` via the JobExplorer, not the full execution table.
 */
@Service
class NLPStatusViewService(
    private val chunkRepository: ContentChunkRepository,
    private val jobExplorer: JobExplorer,
    @Value("\${app.nlp.auto-trigger:true}")
    private val autoTriggerEnabled: Boolean
) {

    companion object {
        const val NLP_JOB_NAME = "nlpProcessingJob"

        /** How many `nlpProcessingJob` instances to walk when building "recent jobs". */
        const val RECENT_JOB_INSTANCES = 10

        /**
         * Step name we inspect for read/write/skip counters in the recent-jobs table.
         * Matches `NLPProcessingJobConfiguration.nlpProcessingStep` (`nlpProcessingStep`).
         */
        const val NLP_STEP_NAME = "nlpProcessingStep"

        /** Analysis statuses whose chunks are eligible for NLP. */
        val NLP_ELIGIBLE_STATUSES: List<AnalysisStatus> =
            listOf(AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC)
    }

    @Transactional(readOnly = true)
    fun loadStatus(): NLPStatusViewDTO {
        val aggregates = chunkRepository.countDashboardAggregates()
        val totalChunks = toLong(aggregates.getOrNull(0))
        val nlpProcessedCount = toLong(aggregates.getOrNull(1))

        val chunksAtIndex = chunkRepository.countByParentAnalysisStatus(AnalysisStatus.INDEX)
        val chunksAtAnalyze = chunkRepository.countByParentAnalysisStatus(AnalysisStatus.ANALYZE)
        val pendingAtAnalyze = chunkRepository.countNlpPendingAtAnalyzeLevel(NLP_ELIGIBLE_STATUSES)

        val coveragePercent = if (totalChunks > 0L) {
            ((nlpProcessedCount.toDouble() / totalChunks.toDouble()) * 100.0).toInt()
        } else {
            0
        }

        val recent = recentNlpExecutions()
        val views = recent.map { toJobView(it) }

        return NLPStatusViewDTO(
            totalChunks = totalChunks,
            chunksAtIndexLevel = chunksAtIndex,
            chunksAtAnalyzeLevel = chunksAtAnalyze,
            nlpProcessedCount = nlpProcessedCount,
            coveragePercent = coveragePercent,
            nlpPendingAtAnalyzeLevel = pendingAtAnalyze,
            autoTriggerEnabled = autoTriggerEnabled,
            lastJob = views.firstOrNull(),
            recentJobs = views
        )
    }

    private fun recentNlpExecutions(): List<JobExecution> {
        // The NLP job name is stable (it's registered as a bean by name), so we
        // don't need the full `jobExplorer.jobNames` walk — go straight to it.
        if (NLP_JOB_NAME !in jobExplorer.jobNames) return emptyList()

        val instances = jobExplorer.getJobInstances(NLP_JOB_NAME, 0, RECENT_JOB_INSTANCES)
        return instances
            .flatMap { jobExplorer.getJobExecutions(it) }
            .sortedByDescending { it.startTime }
            .take(RECENT_JOB_INSTANCES)
    }

    private fun toJobView(execution: JobExecution): NLPJobView {
        val start = execution.startTime?.atZone(ZoneId.systemDefault())?.toInstant()
        val end = execution.endTime?.atZone(ZoneId.systemDefault())?.toInstant()
        val duration = when {
            start != null && end != null -> Duration.between(start, end).seconds
            start != null -> Duration.between(start, java.time.Instant.now()).seconds
            else -> null
        }

        // Sum step counters across all steps under this execution. The NLP job has
        // two steps (NLP + embedding); the operator-facing summary reads the NLP step
        // when present, falling back to the aggregate so the row is never blank.
        val nlpStep = execution.stepExecutions.firstOrNull { it.stepName == NLP_STEP_NAME }
        val readCount = nlpStep?.readCount ?: execution.stepExecutions.sumOf { it.readCount }
        val writeCount = nlpStep?.writeCount ?: execution.stepExecutions.sumOf { it.writeCount }
        val skipCount = nlpStep?.skipCount ?: execution.stepExecutions.sumOf { it.skipCount }

        val triggeredBy = execution.jobParameters.getString("triggeredBy")

        return NLPJobView(
            executionId = execution.id,
            status = execution.status.name,
            exitCode = execution.exitStatus.exitCode,
            startTime = start,
            endTime = end,
            durationSeconds = duration,
            readCount = readCount,
            writeCount = writeCount,
            skipCount = skipCount,
            triggeredBy = triggeredBy
        )
    }

    private fun toLong(value: Any?): Long = when (value) {
        null -> 0L
        is Long -> value
        is Number -> value.toLong()
        else -> value.toString().toLongOrNull() ?: 0L
    }
}
