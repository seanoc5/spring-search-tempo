package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.model.JobRunDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.OffsetDateTime

interface JobRunService {

    fun findAll(filter: String?, pageable: Pageable): Page<JobRunDTO>

    fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable): Page<JobRunDTO>

    fun get(id: Long): JobRunDTO

    fun create(jobRunDTO: JobRunDTO): Long

    fun update(id: Long, jobRunDTO: JobRunDTO)

    fun delete(id: Long)

    fun getLatestRunForConfig(crawlConfigId: Long): JobRunDTO?

    fun getLatestRun(): JobRunDTO?

    /**
     * Create a new job run for a crawl configuration.
     */
    fun startJobRun(crawlConfigId: Long, jobName: String): JobRunDTO

    /**
     * Create a new job run without a crawl configuration.
     * Used for email sync jobs and other non-crawl batch jobs.
     */
    fun startJobRunWithoutConfig(jobName: String, label: String? = null): JobRunDTO

    /**
     * Update job run statistics.
     */
    fun updateJobRunStats(
        jobRunId: Long,
        filesDiscovered: Long? = null,
        filesNew: Long? = null,
        filesUpdated: Long? = null,
        filesSkipped: Long? = null,
        filesError: Long? = null,
        filesAccessDenied: Long? = null,
        foldersDiscovered: Long? = null,
        foldersNew: Long? = null,
        foldersUpdated: Long? = null,
        foldersSkipped: Long? = null
    )

    /**
     * Complete a job run.
     */
    fun completeJobRun(jobRunId: Long, runStatus: RunStatus, errorMessage: String? = null)

    /**
     * Add a warning message to a job run.
     * Warnings are appended (newline-separated) if there are existing warnings.
     */
    fun addWarning(jobRunId: Long, warningMessage: String)

    /**
     * Update the heartbeat timestamp for a running job.
     * Called periodically during job execution to indicate the job is still alive.
     * Used for stale job detection - jobs with no heartbeat for > 2 minutes are considered stale.
     */
    fun updateHeartbeat(jobRunId: Long)

    /**
     * Find running jobs that haven't sent a heartbeat recently.
     * @param staleThresholdMinutes Jobs with no heartbeat for this many minutes are returned
     * @return List of stale job run IDs
     */
    fun findStaleJobRuns(staleThresholdMinutes: Long = 2): List<Long>

    /**
     * Mark a job run as failed due to being stale/orphaned.
     * Updates runStatus to FAILED and sets error message.
     * @return The Spring Batch execution ID if available (for cascading cleanup)
     */
    fun markAsFailed(jobRunId: Long, errorMessage: String): String?

    // Progress tracking methods

    /**
     * Set the expected total number of items to process.
     * Called at job start to enable progress display.
     */
    fun setExpectedTotal(jobRunId: Long, total: Long)

    /**
     * Increment the processed count by the given amount.
     * Called after each chunk is processed.
     */
    fun incrementProcessed(jobRunId: Long, count: Int)

    /**
     * Set the current step name for progress display.
     * e.g., "Fetching headers: INBOX"
     */
    fun setCurrentStep(jobRunId: Long, stepName: String)

    /**
     * Update both processed count and current step in a single call.
     * Reduces database round trips.
     */
    fun updateProgress(jobRunId: Long, processedIncrement: Int, stepName: String? = null)

}
