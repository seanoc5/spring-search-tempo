package com.oconeco.spring_search_tempo.batch.assignment

import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.batch.HeartbeatChunkListener
import com.oconeco.spring_search_tempo.batch.config.BatchTaskExecutorConfig.Companion.DEFAULT_THROTTLE_LIMIT
import com.oconeco.spring_search_tempo.batch.fscrawl.JobRunTrackingListener
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

/**
 * Builder for the analysis assignment job.
 *
 * Assignment is the second phase of the decoupled crawl pipeline:
 * 1. Discovery - Fast filesystem enumeration, SKIP detection only
 * 2. **Assignment** (this job) - Full pattern matching to assign analysisStatus
 * 3. Progressive Analysis - LOCATE → INDEX → NLP → SEMANTIC
 *
 * The assignment job:
 * - Runs after discovery to apply full pattern matching
 * - Assigns analysisStatus (SKIP, LOCATE, INDEX, ANALYZE, SEMANTIC)
 * - Records analysisStatusReason and analysisStatusSetBy
 * - Enables "big picture" decisions before processing
 * - Supports manual override via REST API (separate from batch)
 *
 * Order matters:
 * - Folders are processed first (by URI order for proper inheritance)
 * - Files are processed second (inheriting from parent folders)
 */
@Component
class AnalysisAssignmentJobBuilder(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val folderRepository: FSFolderRepository,
    private val fileRepository: FSFileRepository,
    private val patternMatchingService: PatternMatchingService,
    private val crawlConfigService: CrawlConfigService,
    private val jobRunTrackingListener: JobRunTrackingListener,
    private val heartbeatChunkListener: HeartbeatChunkListener,
    @Qualifier("stepTaskExecutor") private val stepTaskExecutor: TaskExecutor
) {
    companion object {
        private val log = LoggerFactory.getLogger(AnalysisAssignmentJobBuilder::class.java)
    }

    /**
     * Build an assignment job for a crawl definition.
     *
     * @param crawl The crawl definition (provides patterns)
     * @return Configured Spring Batch Job
     */
    fun buildJob(crawl: CrawlDefinition): Job {
        log.info("Building assignment job for: {}", crawl.name)

        val effectivePatterns = crawlConfigService.getEffectivePatterns(crawl)

        return JobBuilder("assignmentJob_${crawl.name}", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobRunTrackingListener)
            .start(buildFolderAssignmentStep(crawl, effectivePatterns))
            .next(buildFileAssignmentStep(crawl, effectivePatterns))
            .build()
    }

    /**
     * Build a global assignment job that processes all items needing assignment.
     * Uses default patterns from configuration.
     *
     * @return Configured Spring Batch Job
     */
    fun buildGlobalJob(): Job {
        log.info("Building global assignment job")

        val defaults = crawlConfigService.getDefaults()
        val defaultPatterns = com.oconeco.spring_search_tempo.base.config.EffectivePatterns(
            folderPatterns = defaults.folderPatterns,
            filePatterns = defaults.filePatterns
        )

        return JobBuilder("globalAssignmentJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobRunTrackingListener)
            .start(buildFolderAssignmentStep(null, defaultPatterns))
            .next(buildFileAssignmentStep(null, defaultPatterns))
            .build()
    }

    /**
     * Build the folder assignment step.
     */
    private fun buildFolderAssignmentStep(
        crawl: CrawlDefinition?,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns
    ): Step {
        val stepName = if (crawl != null) "folderAssignment_${crawl.name}" else "folderAssignment_global"
        log.info("Building folder assignment step: {}", stepName)

        val reader = FolderAssignmentReader(
            folderRepository = folderRepository,
            pageSize = 100
        )

        val processor = FolderAssignmentProcessor(
            folderPatterns = effectivePatterns.folderPatterns,
            patternMatchingService = patternMatchingService,
            folderRepository = folderRepository
        )

        val writer = AssignmentWriter(
            folderRepository = folderRepository,
            fileRepository = fileRepository
        )

        return StepBuilder(stepName, jobRepository)
            .chunk<FolderAssignmentItem, AssignmentResult>(100, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .listener(writer)
            .listener(heartbeatChunkListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }

    /**
     * Build the file assignment step.
     */
    private fun buildFileAssignmentStep(
        crawl: CrawlDefinition?,
        effectivePatterns: com.oconeco.spring_search_tempo.base.config.EffectivePatterns
    ): Step {
        val stepName = if (crawl != null) "fileAssignment_${crawl.name}" else "fileAssignment_global"
        log.info("Building file assignment step: {}", stepName)

        val reader = FileAssignmentReader(
            fileRepository = fileRepository,
            pageSize = 100
        )

        val processor = FileAssignmentProcessor(
            filePatterns = effectivePatterns.filePatterns,
            patternMatchingService = patternMatchingService,
            folderRepository = folderRepository
        )

        val writer = AssignmentWriter(
            folderRepository = folderRepository,
            fileRepository = fileRepository
        )

        return StepBuilder(stepName, jobRepository)
            .chunk<FileAssignmentItem, AssignmentResult>(100, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .listener(writer)
            .listener(heartbeatChunkListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }
}
