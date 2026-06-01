package com.oconeco.spring_search_tempo.batch.ops

import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.model.BrowserProfileDTO
import com.oconeco.spring_search_tempo.batch.assignment.AnalysisAssignmentJobBuilder
import com.oconeco.spring_search_tempo.batch.bookmarkcrawl.BookmarkImportJobBuilder
import com.oconeco.spring_search_tempo.batch.discovery.DiscoveryJobBuilder
import com.oconeco.spring_search_tempo.batch.emailcrawl.EmailCrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.embedding.EmbeddingJobLauncher
import com.oconeco.spring_search_tempo.batch.fscrawl.CrawlOrchestrator
import com.oconeco.spring_search_tempo.batch.nlp.NLPJobLauncher
import com.oconeco.spring_search_tempo.batch.onedrivesync.OneDriveSyncOrchestrator
import com.oconeco.spring_search_tempo.batch.progressive.ProgressiveAnalysisJobBuilder
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.stereotype.Service

@Service
class BatchAdminOperationsService(
    private val nlpJobLauncher: NLPJobLauncher,
    private val embeddingJobLauncher: EmbeddingJobLauncher,
    private val crawlOrchestrator: CrawlOrchestrator,
    private val emailCrawlOrchestrator: EmailCrawlOrchestrator,
    private val oneDriveSyncOrchestrator: OneDriveSyncOrchestrator,
    private val jobLauncher: JobLauncher,
    private val discoveryJobBuilder: DiscoveryJobBuilder,
    private val analysisAssignmentJobBuilder: AnalysisAssignmentJobBuilder,
    private val progressiveAnalysisJobBuilder: ProgressiveAnalysisJobBuilder,
    private val bookmarkImportJobBuilder: BookmarkImportJobBuilder
) {

    fun launchNlp(triggeredBy: String = "batch-admin"): JobExecution =
        nlpJobLauncher.launchNLPJob(triggeredBy)

    fun launchEmbedding(triggeredBy: String = "batch-admin"): JobExecution =
        embeddingJobLauncher.launchEmbeddingJob(triggeredBy)

    fun executeAllCrawls(): Map<String, JobExecution> =
        crawlOrchestrator.executeAllCrawls()

    fun executeCrawlsByName(crawlName: String): Map<String, JobExecution> =
        crawlOrchestrator.executeCrawlsByName(crawlName)

    fun launchEmailQuickSyncForAccount(accountId: Long): JobExecution =
        emailCrawlOrchestrator.runQuickSyncForAccount(accountId)

    fun launchEmailQuickSyncAll(): Map<String, String> =
        emailCrawlOrchestrator.runQuickSync()

    fun launchOneDriveSyncAll(): Map<Long, JobExecution> =
        oneDriveSyncOrchestrator.runSyncExecutions()

    fun launchOneDriveSyncForAccount(accountId: Long): JobExecution =
        oneDriveSyncOrchestrator.runSyncExecutionForAccount(accountId)

    fun launchDiscoveryForCrawl(crawl: CrawlDefinition): JobExecution {
        val job = discoveryJobBuilder.buildJob(crawl)
        val params = JobParametersBuilder()
            .addString("crawlName", crawl.name)
            .addString("startPaths", crawl.startPaths.joinToString(","))
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()
        return jobLauncher.run(job, params)
    }

    fun launchAssignmentForCrawl(crawl: CrawlDefinition): JobExecution {
        val job = analysisAssignmentJobBuilder.buildJob(crawl)
        val params = JobParametersBuilder()
            .addString("crawlName", crawl.name)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()
        return jobLauncher.run(job, params)
    }

    fun launchGlobalAssignment(): JobExecution {
        val job = analysisAssignmentJobBuilder.buildGlobalJob()
        val params = JobParametersBuilder()
            .addLong("timestamp", System.currentTimeMillis())
            .addString("triggeredBy", "batch-admin")
            .toJobParameters()
        return jobLauncher.run(job, params)
    }

    fun launchProgressiveAnalysis(): JobExecution {
        val job = progressiveAnalysisJobBuilder.buildJob(processAll = true)
        val params = JobParametersBuilder()
            .addLong("timestamp", System.currentTimeMillis())
            .addString("triggeredBy", "batch-admin")
            .toJobParameters()
        return jobLauncher.run(job, params)
    }

    fun launchBookmarkImportForProfile(profile: BrowserProfileDTO): JobExecution {
        val profileId = profile.id ?: throw IllegalArgumentException("Browser profile ID is required")
        val job = bookmarkImportJobBuilder.buildJob(profile)
        val params = JobParametersBuilder()
            .addString("profileId", profileId.toString())
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters()
        return jobLauncher.run(job, params)
    }
}
