package com.oconeco.spring_search_tempo.batch.discovery

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.RecentCrawlSkipChecker
import com.oconeco.spring_search_tempo.batch.HeartbeatChunkListener
import com.oconeco.spring_search_tempo.batch.config.BatchTaskExecutorConfig.Companion.DEFAULT_THROTTLE_LIMIT
import com.oconeco.spring_search_tempo.batch.fscrawl.JobRunTrackingListener
import com.oconeco.spring_search_tempo.batch.fscrawl.PathValidationListener
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
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Builder for the discovery job.
 *
 * Discovery is the first phase of the decoupled crawl pipeline:
 * 1. **Discovery** (this job) - Fast filesystem enumeration, SKIP detection only
 * 2. Assignment - Full pattern matching to assign analysisStatus
 * 3. Progressive Analysis - LOCATE → INDEX → NLP → SEMANTIC
 *
 * The discovery job:
 * - Walks the filesystem efficiently
 * - Checks only SKIP patterns (fast, performance-critical)
 * - Collects basic metadata (size, timestamps, permissions)
 * - Sets locatedAt timestamp and skipDetected flag
 * - Does NOT perform text extraction or full pattern matching
 *
 * SKIP Optimization:
 * Folders matching SKIP patterns are not enumerated - their children
 * are never listed from the filesystem, providing significant
 * performance improvement for .git, node_modules, etc.
 */
@Component
class DiscoveryJobBuilder(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val folderService: FSFolderService,
    private val fileService: FSFileService,
    private val folderRepository: FSFolderRepository,
    private val fileRepository: FSFileRepository,
    private val patternMatchingService: PatternMatchingService,
    private val crawlConfigService: CrawlConfigService,
    private val jobRunService: JobRunService,
    private val jobRunTrackingListener: JobRunTrackingListener,
    private val heartbeatChunkListener: HeartbeatChunkListener,
    @Qualifier("stepTaskExecutor") private val stepTaskExecutor: TaskExecutor
) {
    companion object {
        private val log = LoggerFactory.getLogger(DiscoveryJobBuilder::class.java)
    }

    /**
     * Build a discovery job for a crawl definition.
     *
     * @param crawl The crawl definition
     * @param crawlConfigId Optional database ID (enables recent crawl skip)
     * @param freshnessHours Hours threshold for recent crawl skip
     * @return Configured Spring Batch Job
     */
    fun buildJob(
        crawl: CrawlDefinition,
        crawlConfigId: Long? = null,
        freshnessHours: Int? = null
    ): Job {
        log.info(
            "Building discovery job for: {} with {} start paths",
            crawl.name, crawl.startPaths.size
        )

        val defaults = crawlConfigService.getDefaults()
        val effectivePatterns = crawlConfigService.getEffectivePatterns(crawl)
        val maxDepth = crawl.getMaxDepth(defaults)
        val followLinks = crawl.getFollowLinks(defaults)
        val effectiveFreshnessHours = freshnessHours ?: defaults.recentCrawlSkipHours

        val startPaths = crawl.startPaths.map { Path(it) }

        val pathValidationListener = PathValidationListener(startPaths, jobRunService)

        return JobBuilder("discoveryJob_${crawl.name}", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobRunTrackingListener)
            .listener(pathValidationListener)
            .start(
                buildDiscoveryStep(
                    crawl = crawl,
                    startPaths = startPaths,
                    maxDepth = maxDepth,
                    followLinks = followLinks,
                    skipPatterns = effectivePatterns.folderPatterns.skip,
                    crawlConfigId = crawlConfigId,
                    freshnessHours = effectiveFreshnessHours
                )
            )
            .build()
    }

    /**
     * Build the discovery step.
     */
    private fun buildDiscoveryStep(
        crawl: CrawlDefinition,
        startPaths: List<Path>,
        maxDepth: Int,
        followLinks: Boolean,
        skipPatterns: List<String>,
        crawlConfigId: Long?,
        freshnessHours: Int
    ): Step {
        log.info(
            "Building discovery step: {} start paths, maxDepth={}, {} SKIP patterns",
            startPaths.size, maxDepth, skipPatterns.size
        )

        val recentCrawlChecker = if (crawlConfigId != null) {
            RecentCrawlSkipChecker(
                fsFolderRepository = folderRepository,
                currentCrawlConfigId = crawlConfigId,
                freshnessHours = freshnessHours
            )
        } else {
            null
        }

        val reader = DiscoveryReader(
            startPaths = startPaths,
            maxDepth = maxDepth,
            followLinks = followLinks,
            skipPatterns = skipPatterns,
            patternMatchingService = patternMatchingService,
            recentCrawlChecker = recentCrawlChecker
        )

        val processor = DiscoveryProcessor(startPaths = startPaths)

        val writer = DiscoveryWriter(
            folderService = folderService,
            fileService = fileService,
            folderRepository = folderRepository,
            fileRepository = fileRepository
        )

        return StepBuilder("discovery_${crawl.name}", jobRepository)
            .chunk<DiscoveryFolderItem, DiscoveryResult>(100, transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .listener(writer) // For statistics
            .listener(heartbeatChunkListener)
            .taskExecutor(stepTaskExecutor)
            .throttleLimit(DEFAULT_THROTTLE_LIMIT)
            .build()
    }
}
