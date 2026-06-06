package com.oconeco.spring_search_tempo.web.service

import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Builds the view model for the EmailAccount live sync-status panel (issue #68).
 *
 * The panel replaces the legacy flash-message banner ("sync started... Status: JobExecution:
 * id=297, version=0, startTime=null ...") with a real polling view backed by JobExplorer.
 * It surfaces the latest `emailQuickSync_<accountId>` execution — terminal or in-flight —
 * with a color-coded badge, start/duration, and (on failure) the truncated exit message.
 */
@Service
class EmailSyncStatusViewService(
    private val jobExplorer: JobExplorer,
) {
    companion object {
        /** Max chars of exit description / exception text surfaced on the panel. */
        const val EXIT_MESSAGE_LIMIT = 200

        /** Job-name prefix used by EmailCrawlOrchestrator/EmailQuickSyncJobBuilder. */
        const val JOB_NAME_PREFIX = "emailQuickSync_"

        fun jobName(accountId: Long) = "$JOB_NAME_PREFIX$accountId"
    }

    /**
     * Resolve the panel view for [accountId], or null if no quick-sync execution
     * has ever been recorded for this account. The controller renders a "never run"
     * placeholder in that case.
     */
    fun load(accountId: Long): EmailSyncStatusView? {
        val name = jobName(accountId)
        if (name !in jobExplorer.jobNames) return null

        // Walk a small number of recent instances to find the latest execution by start time.
        // We only need the most-recent one; 5 instances is generous for a per-account job.
        val instances = jobExplorer.getJobInstances(name, 0, 5)
        if (instances.isEmpty()) return null

        val latest = instances
            .flatMap { jobExplorer.getJobExecutions(it) }
            .maxByOrNull { execStartOrCreate(it) }
            ?: return null

        return toView(latest)
    }

    private fun execStartOrCreate(exec: JobExecution): Instant =
        exec.startTime?.atZone(ZoneId.systemDefault())?.toInstant()
            ?: exec.createTime?.atZone(ZoneId.systemDefault())?.toInstant()
            ?: Instant.EPOCH

    private fun toView(exec: JobExecution): EmailSyncStatusView {
        val zone = ZoneId.systemDefault()
        val startInstant = exec.startTime?.atZone(zone)?.toInstant()
        val endInstant = exec.endTime?.atZone(zone)?.toInstant()
        val durationSeconds = when {
            startInstant != null && endInstant != null ->
                Duration.between(startInstant, endInstant).seconds
            startInstant != null -> Duration.between(startInstant, Instant.now()).seconds
            else -> null
        }

        val status = exec.status
        val terminal = isTerminal(status)

        // Failure messaging: combine the job-level exit description with any step-level
        // failure exceptions / non-COMPLETED step exit descriptions. The user-visible
        // string is truncated to EXIT_MESSAGE_LIMIT chars (UI hint, not security).
        val exitMessage = if (terminal && status != BatchStatus.COMPLETED) {
            collectExitMessage(exec)?.take(EXIT_MESSAGE_LIMIT)
        } else null

        return EmailSyncStatusView(
            executionId = exec.id,
            status = status.name,
            badgeClass = badgeClass(status),
            terminal = terminal,
            startedAt = exec.startTime?.atOffset(OffsetDateTime.now().offset),
            endedAt = exec.endTime?.atOffset(OffsetDateTime.now().offset),
            durationSeconds = durationSeconds,
            exitMessage = exitMessage,
        )
    }

    private fun isTerminal(status: BatchStatus): Boolean =
        status == BatchStatus.COMPLETED ||
            status == BatchStatus.FAILED ||
            status == BatchStatus.STOPPED ||
            status == BatchStatus.ABANDONED

    private fun badgeClass(status: BatchStatus): String = when (status) {
        BatchStatus.COMPLETED -> "bg-success"
        BatchStatus.FAILED -> "bg-danger"
        BatchStatus.STOPPED, BatchStatus.ABANDONED -> "bg-secondary"
        BatchStatus.STARTING, BatchStatus.STARTED -> "bg-primary"
        else -> "bg-info"
    }

    private fun collectExitMessage(exec: JobExecution): String? {
        val parts = linkedSetOf<String>()
        exec.exitStatus.exitDescription
            ?.takeIf { it.isNotBlank() }
            ?.let { parts += it }
        for (e in exec.allFailureExceptions) {
            e.message?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        for (step in exec.stepExecutions) {
            for (e in step.failureExceptions) {
                e.message?.takeIf { it.isNotBlank() }?.let { parts += it }
            }
            if (step.exitStatus.exitCode != "COMPLETED") {
                step.exitStatus.exitDescription
                    ?.takeIf { it.isNotBlank() }
                    ?.let { parts += it }
            }
        }
        return parts.joinToString("; ").takeIf { it.isNotBlank() }
    }
}

/**
 * View model for the EmailAccount live sync-status panel (issue #68).
 *
 * Fields are sized for direct template binding — no formatting helpers needed.
 *
 * @property terminal True when the panel can stop polling. The fragment sets
 *   `hx-trigger="every 2s"` only while terminal=false.
 */
data class EmailSyncStatusView(
    val executionId: Long?,
    val status: String,
    val badgeClass: String,
    val terminal: Boolean,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val durationSeconds: Long?,
    val exitMessage: String?,
)
