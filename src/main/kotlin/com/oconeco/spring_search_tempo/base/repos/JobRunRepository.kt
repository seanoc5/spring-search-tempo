package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.JobRun
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

interface JobRunRepository : JpaRepository<JobRun, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<JobRun>

    /**
     * Find all job runs with CrawlConfig eagerly fetched.
     * Avoids LazyInitializationException when accessing crawlConfig properties.
     */
    @Query("""
        SELECT jr FROM JobRun jr
        LEFT JOIN FETCH jr.crawlConfig
    """,
        countQuery = "SELECT COUNT(jr) FROM JobRun jr"
    )
    fun findAllWithCrawlConfig(pageable: Pageable): Page<JobRun>

    /**
     * Find job runs by ID filter with CrawlConfig eagerly fetched.
     */
    @Query("""
        SELECT jr FROM JobRun jr
        LEFT JOIN FETCH jr.crawlConfig
        WHERE jr.id = :id
    """,
        countQuery = "SELECT COUNT(jr) FROM JobRun jr WHERE jr.id = :id"
    )
    fun findByIdWithCrawlConfig(id: Long?, pageable: Pageable): Page<JobRun>

    fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable): Page<JobRun>

    fun findByCrawlConfigId(crawlConfigId: Long): List<JobRun>

    fun findByRunStatus(runStatus: RunStatus, pageable: Pageable): Page<JobRun>

    fun findByJobName(jobName: String, pageable: Pageable): Page<JobRun>

    fun findFirstByCrawlConfigIdOrderByStartTimeDesc(crawlConfigId: Long): JobRun?

    fun findFirstByOrderByStartTimeDesc(): JobRun?

    /**
     * Update heartbeat timestamp for a job run.
     * Uses direct update query for efficiency (no need to load the entity).
     */
    @Modifying
    @Transactional
    @Query("UPDATE JobRun jr SET jr.lastHeartbeatAt = :timestamp WHERE jr.id = :jobRunId")
    fun updateHeartbeat(@Param("jobRunId") jobRunId: Long, @Param("timestamp") timestamp: OffsetDateTime)

    /**
     * Find running jobs that haven't sent a heartbeat recently.
     * A job is considered stale if:
     * - Status is RUNNING
     * - Either: lastHeartbeatAt is null (never sent heartbeat) OR lastHeartbeatAt < threshold
     *
     * For jobs without heartbeat, we fall back to checking startTime.
     */
    @Query("""
        SELECT jr.id FROM JobRun jr
        WHERE jr.runStatus = 'RUNNING'
          AND (
              (jr.lastHeartbeatAt IS NOT NULL AND jr.lastHeartbeatAt < :threshold)
              OR (jr.lastHeartbeatAt IS NULL AND jr.startTime < :threshold)
          )
    """)
    fun findStaleRunningJobs(@Param("threshold") threshold: OffsetDateTime): List<Long>

    /**
     * Find all currently running job runs.
     */
    fun findByRunStatus(runStatus: RunStatus): List<JobRun>

    // Progress tracking methods

    /**
     * Set the expected total for progress tracking.
     */
    @Modifying
    @Transactional
    @Query("UPDATE JobRun jr SET jr.expectedTotal = :total WHERE jr.id = :jobRunId")
    fun updateExpectedTotal(@Param("jobRunId") jobRunId: Long, @Param("total") total: Long)

    /**
     * Increment the processed count.
     */
    @Modifying
    @Transactional
    @Query("UPDATE JobRun jr SET jr.processedCount = jr.processedCount + :increment WHERE jr.id = :jobRunId")
    fun incrementProcessedCount(@Param("jobRunId") jobRunId: Long, @Param("increment") increment: Long)

    /**
     * Update the current step name.
     */
    @Modifying
    @Transactional
    @Query("UPDATE JobRun jr SET jr.currentStepName = :stepName WHERE jr.id = :jobRunId")
    fun updateCurrentStep(@Param("jobRunId") jobRunId: Long, @Param("stepName") stepName: String)

    /**
     * Update both processed count and step name in one query.
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE JobRun jr
        SET jr.processedCount = jr.processedCount + :increment,
            jr.currentStepName = :stepName
        WHERE jr.id = :jobRunId
    """)
    fun updateProgressWithStep(
        @Param("jobRunId") jobRunId: Long,
        @Param("increment") increment: Long,
        @Param("stepName") stepName: String
    )

    /**
     * Find active (RUNNING) email sync job for a specific account.
     * Job names follow pattern: emailQuickSyncJob-{accountId}
     */
    @Query("""
        SELECT jr FROM JobRun jr
        WHERE jr.runStatus = 'RUNNING'
          AND jr.jobName LIKE CONCAT('emailQuickSyncJob-', :accountId, '%')
        ORDER BY jr.startTime DESC
    """)
    fun findActiveEmailSyncJobForAccount(@Param("accountId") accountId: Long): JobRun?

    /**
     * Find all active email sync jobs.
     */
    @Query("""
        SELECT jr FROM JobRun jr
        WHERE jr.runStatus = 'RUNNING'
          AND jr.jobName LIKE 'emailQuickSyncJob-%'
    """)
    fun findAllActiveEmailSyncJobs(): List<JobRun>

    /**
     * Find active (RUNNING) OneDrive sync job for a specific account.
     * Job names follow pattern: oneDriveSync_{accountId}
     */
    @Query("""
        SELECT jr FROM JobRun jr
        WHERE jr.runStatus = 'RUNNING'
          AND jr.jobName LIKE CONCAT('oneDriveSync_', :accountId, '%')
        ORDER BY jr.startTime DESC
    """)
    fun findActiveOneDriveSyncJobForAccount(@Param("accountId") accountId: Long): JobRun?

    /**
     * Find all active OneDrive sync jobs.
     */
    @Query("""
        SELECT jr FROM JobRun jr
        WHERE jr.runStatus = 'RUNNING'
          AND jr.jobName LIKE 'oneDriveSync_%'
    """)
    fun findAllActiveOneDriveSyncJobs(): List<JobRun>

}
