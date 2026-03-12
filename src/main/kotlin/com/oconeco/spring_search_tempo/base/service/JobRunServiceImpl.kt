package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.JobRun
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.JobRunDTO
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.JobRunRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

@Service
class JobRunServiceImpl(
    private val jobRunRepository: JobRunRepository,
    private val crawlConfigRepository: CrawlConfigRepository,
    private val jobRunMapper: JobRunMapper,
    private val meterRegistry: MeterRegistry
) : JobRunService {

    override fun findAll(filter: String?, pageable: Pageable): Page<JobRunDTO> {
        // Use JOIN FETCH to eagerly load CrawlConfig and avoid LazyInitializationException
        val page: Page<JobRun> = if (filter != null) {
            val filterId = filter.toLongOrNull()
            jobRunRepository.findByIdWithCrawlConfig(filterId, pageable)
        } else {
            jobRunRepository.findAllWithCrawlConfig(pageable)
        }
        return PageImpl(
            page.content.map { jobRun ->
                jobRunMapper.updateJobRunDTO(jobRun, JobRunDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable): Page<JobRunDTO> {
        val page = jobRunRepository.findByCrawlConfigId(crawlConfigId, pageable)
        return PageImpl(
            page.content.map { jobRun ->
                jobRunMapper.updateJobRunDTO(jobRun, JobRunDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun get(id: Long): JobRunDTO = jobRunRepository.findById(id)
        .map { jobRun ->
            jobRunMapper.updateJobRunDTO(jobRun, JobRunDTO())
        }
        .orElseThrow { NotFoundException() }

    override fun create(jobRunDTO: JobRunDTO): Long {
        val jobRun = JobRun()
        jobRunMapper.updateJobRun(jobRunDTO, jobRun, crawlConfigRepository)
        return jobRunRepository.save(jobRun).id!!
    }

    override fun update(id: Long, jobRunDTO: JobRunDTO) {
        val jobRun = jobRunRepository.findById(id)
            .orElseThrow { NotFoundException() }
        jobRunMapper.updateJobRun(jobRunDTO, jobRun, crawlConfigRepository)
        jobRunRepository.save(jobRun)
    }

    override fun delete(id: Long) {
        val jobRun = jobRunRepository.findById(id)
            .orElseThrow { NotFoundException() }
        jobRunRepository.delete(jobRun)
    }

    override fun getLatestRunForConfig(crawlConfigId: Long): JobRunDTO? {
        val jobRun = jobRunRepository.findFirstByCrawlConfigIdOrderByStartTimeDesc(crawlConfigId)
            ?: return null
        return jobRunMapper.updateJobRunDTO(jobRun, JobRunDTO())
    }

    override fun getLatestRun(): JobRunDTO? {
        val jobRun = jobRunRepository.findFirstByOrderByStartTimeDesc() ?: return null
        return jobRunMapper.updateJobRunDTO(jobRun, JobRunDTO())
    }

    override fun startJobRun(crawlConfigId: Long, jobName: String): JobRunDTO {
        val crawlConfig = crawlConfigRepository.findById(crawlConfigId)
            .orElseThrow { NotFoundException("CrawlConfig not found: $crawlConfigId") }

        val now = OffsetDateTime.now()
        val jobRun = JobRun().apply {
            this.crawlConfig = crawlConfig
            this.jobName = jobName
            this.label = crawlConfig.label ?: crawlConfig.name
            this.startTime = now
            this.lastHeartbeatAt = now  // Initialize heartbeat at start
            this.runStatus = RunStatus.RUNNING
            this.status = Status.IN_PROGRESS
            this.uri = "job-run:$jobName:${System.currentTimeMillis()}"
            this.version = 0L
        }

        val savedJobRun = jobRunRepository.save(jobRun)
        recordJobRunStarted(savedJobRun.jobName)
        return jobRunMapper.updateJobRunDTO(savedJobRun, JobRunDTO())
    }

    override fun startJobRunWithoutConfig(jobName: String, label: String?): JobRunDTO {
        val now = OffsetDateTime.now()
        val jobRun = JobRun().apply {
            this.crawlConfig = null  // No crawl config for email/other jobs
            this.jobName = jobName
            this.label = label ?: jobName
            this.startTime = now
            this.lastHeartbeatAt = now  // Initialize heartbeat at start
            this.runStatus = RunStatus.RUNNING
            this.status = Status.IN_PROGRESS
            this.uri = "job-run:$jobName:${System.currentTimeMillis()}"
            this.version = 0L
        }

        val savedJobRun = jobRunRepository.save(jobRun)
        recordJobRunStarted(savedJobRun.jobName)
        return jobRunMapper.updateJobRunDTO(savedJobRun, JobRunDTO())
    }

    override fun updateJobRunStats(
        jobRunId: Long,
        filesDiscovered: Long?,
        filesNew: Long?,
        filesUpdated: Long?,
        filesSkipped: Long?,
        filesError: Long?,
        filesAccessDenied: Long?,
        foldersDiscovered: Long?,
        foldersNew: Long?,
        foldersUpdated: Long?,
        foldersSkipped: Long?
    ) {
        val jobRun = jobRunRepository.findById(jobRunId)
            .orElseThrow { NotFoundException("JobRun not found: $jobRunId") }

        filesDiscovered?.let { jobRun.filesDiscovered = it }
        filesNew?.let { jobRun.filesNew = it }
        filesUpdated?.let { jobRun.filesUpdated = it }
        filesSkipped?.let { jobRun.filesSkipped = it }
        filesError?.let { jobRun.filesError = it }
        filesAccessDenied?.let { jobRun.filesAccessDenied = it }
        foldersDiscovered?.let { jobRun.foldersDiscovered = it }
        foldersNew?.let { jobRun.foldersNew = it }
        foldersUpdated?.let { jobRun.foldersUpdated = it }
        foldersSkipped?.let { jobRun.foldersSkipped = it }

        jobRun.totalItems = jobRun.filesDiscovered + jobRun.foldersDiscovered

        jobRunRepository.save(jobRun)
    }

    override fun completeJobRun(jobRunId: Long, runStatus: RunStatus, errorMessage: String?) {
        val jobRun = jobRunRepository.findById(jobRunId)
            .orElseThrow { NotFoundException("JobRun not found: $jobRunId") }

        jobRun.finishTime = OffsetDateTime.now()
        jobRun.runStatus = runStatus
        jobRun.status = when (runStatus) {
            RunStatus.COMPLETED -> Status.CURRENT
            RunStatus.FAILED -> Status.FAILED
            RunStatus.CANCELLED -> Status.FAILED
            else -> Status.IN_PROGRESS
        }
        jobRun.errorMessage = errorMessage

        jobRunRepository.save(jobRun)
        recordJobRunFinished(jobRun.jobName, runStatus, jobRun.startTime, jobRun.finishTime)
    }

    override fun addWarning(jobRunId: Long, warningMessage: String) {
        val jobRun = jobRunRepository.findById(jobRunId)
            .orElseThrow { NotFoundException("JobRun not found: $jobRunId") }

        // Append to existing warnings or set new
        jobRun.warningMessage = if (jobRun.warningMessage.isNullOrBlank()) {
            warningMessage
        } else {
            "${jobRun.warningMessage}\n$warningMessage"
        }

        jobRunRepository.save(jobRun)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun updateHeartbeat(jobRunId: Long) {
        jobRunRepository.updateHeartbeat(jobRunId, OffsetDateTime.now())
    }

    override fun findStaleJobRuns(staleThresholdMinutes: Long): List<Long> {
        val threshold = OffsetDateTime.now().minusMinutes(staleThresholdMinutes)
        return jobRunRepository.findStaleRunningJobs(threshold)
    }

    override fun markAsFailed(jobRunId: Long, errorMessage: String): String? {
        val jobRun = jobRunRepository.findById(jobRunId)
            .orElseThrow { NotFoundException("JobRun not found: $jobRunId") }

        jobRun.finishTime = OffsetDateTime.now()
        jobRun.runStatus = RunStatus.FAILED
        jobRun.status = Status.FAILED
        jobRun.errorMessage = errorMessage

        jobRunRepository.save(jobRun)
        recordJobRunFinished(jobRun.jobName, RunStatus.FAILED, jobRun.startTime, jobRun.finishTime)

        // Return Spring Batch execution ID if available (for cascading cleanup)
        return jobRun.springBatchJobExecutionId
    }

    // Progress tracking methods

    override fun setExpectedTotal(jobRunId: Long, total: Long) {
        jobRunRepository.updateExpectedTotal(jobRunId, total)
    }

    override fun incrementProcessed(jobRunId: Long, count: Int) {
        jobRunRepository.incrementProcessedCount(jobRunId, count.toLong())
    }

    override fun setCurrentStep(jobRunId: Long, stepName: String) {
        jobRunRepository.updateCurrentStep(jobRunId, stepName)
    }

    override fun updateProgress(jobRunId: Long, processedIncrement: Int, stepName: String?) {
        if (stepName != null) {
            jobRunRepository.updateProgressWithStep(jobRunId, processedIncrement.toLong(), stepName)
        } else {
            jobRunRepository.incrementProcessedCount(jobRunId, processedIncrement.toLong())
        }
    }

    private fun recordJobRunStarted(jobName: String?) {
        meterRegistry.counter(
            "tempo.batch.jobrun.started",
            "job_name",
            normalizeJobName(jobName)
        ).increment()
    }

    private fun recordJobRunFinished(
        jobName: String?,
        status: RunStatus,
        startTime: OffsetDateTime?,
        finishTime: OffsetDateTime?
    ) {
        val normalizedJob = normalizeJobName(jobName)
        meterRegistry.counter(
            "tempo.batch.jobrun.completed",
            "job_name",
            normalizedJob,
            "status",
            status.name
        ).increment()

        if (startTime != null && finishTime != null) {
            val duration = Duration.between(startTime, finishTime)
            if (!duration.isNegative) {
                Timer.builder("tempo.batch.jobrun.duration")
                    .description("JobRun duration from start to finish")
                    .tag("job_name", normalizedJob)
                    .tag("status", status.name)
                    .register(meterRegistry)
                    .record(duration)
            }
        }
    }

    private fun normalizeJobName(jobName: String?): String {
        if (jobName.isNullOrBlank()) return "unknown"
        return when {
            jobName.startsWith("emailQuickSyncJob-") -> "emailQuickSyncJob"
            jobName.startsWith("oneDriveSync_") -> "oneDriveSync"
            else -> jobName.replace(Regex("[-_]\\d+$"), "")
        }
    }

}
