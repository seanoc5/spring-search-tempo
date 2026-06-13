package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.base.domain.FolderAuditRunStatus
import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.base.repos.FolderSnapshotRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.time.OffsetDateTime

/**
 * Tasklet for the filesystem folder audit (issue #103).
 *
 * Reads `runId` from the job parameters, walks every enabled crawl
 * config's start paths under the audit visitor, then writes the
 * final aggregates back onto the [com.oconeco.spring_search_tempo.base.domain.FolderAuditRun].
 *
 * Per-crawl SKIP and INDEX/ANALYZE/SEMANTIC patterns are merged through
 * [CrawlConfigService.getEffectivePatterns] so the audit sees exactly
 * the same pattern surface as the live crawl — that's what makes its
 * counts comparable.
 */
@Component
class FilesystemFolderAuditTasklet(
    private val folderAuditRunRepository: FolderAuditRunRepository,
    private val folderSnapshotRepository: FolderSnapshotRepository,
    private val patternMatchingService: PatternMatchingService,
    private val crawlConfigService: CrawlConfigService,
    @Value("\${app.audit.hidden-gem-peek-depth:1}")
    private val peekDepth: Int
) : Tasklet {

    companion object {
        private val log = LoggerFactory.getLogger(FilesystemFolderAuditTasklet::class.java)
    }

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val runId = chunkContext.stepContext.jobParameters["runId"] as? Long
            ?: throw IllegalStateException("Folder audit tasklet requires 'runId' job parameter")

        val run = folderAuditRunRepository.findById(runId).orElseThrow {
            IllegalStateException("FolderAuditRun #$runId not found")
        }

        try {
            val crawls = crawlConfigService.getEnabledCrawls()
            log.info(
                "Folder audit runId={} starting walk of {} enabled crawls (peekDepth={})",
                runId, crawls.size, peekDepth
            )

            var total = 0L
            var skipRoots = 0L
            var hiddenGems = 0L

            for (crawl in crawls) {
                val effective = crawlConfigService.getEffectivePatterns(crawl)
                val skipPatterns = effective.folderPatterns.skip
                val indexPatterns = effective.folderPatterns.index +
                        effective.folderPatterns.analyze +
                        effective.folderPatterns.semantic

                for (startPath in crawl.startPaths) {
                    val path = Paths.get(startPath)
                    if (!Files.exists(path) || !Files.isDirectory(path)) {
                        log.warn(
                            "Audit runId={} skipping non-directory start path: {}",
                            runId, startPath
                        )
                        continue
                    }

                    val visitor = FolderAuditVisitor(
                        run = run,
                        startPath = path,
                        skipPatterns = skipPatterns,
                        indexPatterns = indexPatterns,
                        peekDepth = peekDepth,
                        patternMatchingService = patternMatchingService,
                        folderSnapshotRepository = folderSnapshotRepository
                    )

                    log.info(
                        "Audit runId={} walking crawl={} startPath={}",
                        runId, crawl.name, startPath
                    )
                    Files.walkFileTree(path, visitor)
                    visitor.flush()

                    total += visitor.totalFolders
                    skipRoots += visitor.skipSubtreeCount
                    hiddenGems += visitor.hiddenGemCount
                }
            }

            run.totalFolders = total
            run.skipSubtreeCount = skipRoots
            run.hiddenGemCount = hiddenGems
            run.status = FolderAuditRunStatus.COMPLETED
            run.finished = OffsetDateTime.now()
            folderAuditRunRepository.save(run)

            log.info(
                "Folder audit runId={} completed: total={} skipRoots={} hiddenGems={}",
                runId, total, skipRoots, hiddenGems
            )
        } catch (e: Exception) {
            log.error("Folder audit runId={} failed", runId, e)
            run.status = FolderAuditRunStatus.FAILED
            run.finished = OffsetDateTime.now()
            run.errorMessage = e.message?.take(2000)
            folderAuditRunRepository.save(run)
            throw e
        }

        return RepeatStatus.FINISHED
    }
}
