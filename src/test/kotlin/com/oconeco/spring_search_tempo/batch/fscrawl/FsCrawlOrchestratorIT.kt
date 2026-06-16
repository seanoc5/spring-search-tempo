package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorRunStatus
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOutcomeStatus
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.FsCrawlOrchestratorOutcomeRepository
import com.oconeco.spring_search_tempo.base.repos.FsCrawlOrchestratorRunRepository
import com.oconeco.spring_search_tempo.testfixtures.CrawlTreeFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.batch.core.Job
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

/**
 * End-to-end IT for `FsCrawlOrchestrator` (issue #139, AC #f).
 *
 *  - Persists 3 enabled `CrawlConfig` rows pointing at disjoint subtrees
 *    of the canonical crawl fixture (issue #115).
 *  - Calls `runAllEnabledCrawls` synchronously (bypassing the async
 *    submit-handle path that's exercised by the REST layer; we want the
 *    assertions to see terminal state).
 *  - Asserts every per-crawl outcome is captured, the aggregate counts
 *    are correct, and a per-crawl failure does NOT abort siblings.
 *
 * Why bypass async submit: the orchestrator's `submitAllEnabledCrawls`
 * spawns a background thread, and the IT would need to poll for
 * completion. `runAllEnabledCrawls(runId)` is the same code path the
 * background thread runs — testing it synchronously gives us the same
 * coverage without timing flake.
 *
 * Pattern config is intentionally minimal (LOCATE-only) so each crawl
 * finishes quickly: we only care about the orchestrator's accounting,
 * not the underlying crawl mechanics, which are already covered by
 * AnalysisStatusEndToEndIT (#116).
 *
 * NLP auto-trigger is disabled so the orchestrator's per-crawl waits
 * aren't blocked on async NLP jobs that wouldn't matter to this test.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@TestPropertySource(properties = ["app.nlp.auto-trigger=false"])
@DirtiesContext
@DisplayName("FsCrawlOrchestrator end-to-end (issue #139)")
class FsCrawlOrchestratorIT : BaseIT() {

    private lateinit var tmp: Path

    @Autowired lateinit var fsCrawlOrchestrator: FsCrawlOrchestrator
    @Autowired lateinit var crawlConfigRepository: CrawlConfigRepository
    @Autowired lateinit var orchestratorRunRepository: FsCrawlOrchestratorRunRepository
    @Autowired lateinit var orchestratorOutcomeRepository: FsCrawlOrchestratorOutcomeRepository

    /**
     * Spy on the job builder so the failure-isolation test can throw on
     * one specific crawl name without breaking the other two. Stubbing
     * here keeps the failure injection sharp and easy to revisit.
     */
    @MockitoSpyBean
    lateinit var fsCrawlJobBuilder: FsCrawlJobBuilder

    @AfterEach
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanupFixture() {
        if (::tmp.isInitialized && Files.exists(tmp)) {
            tmp.deleteRecursively()
        }
    }

    @Test
    @DisplayName("3 enabled crawls against disjoint subtrees → all 3 SUCCEEDED, aggregate counts correct")
    fun threeEnabledCrawlsAllSucceed() {
        val root = buildFixture()
        val configIds = persistEnabledCrawls(
            root,
            listOf("docs", "analyzed", "metadata-only")
        )

        val run = fsCrawlOrchestrator.runAllEnabledCrawls(triggeredBy = "it")

        assertThat(run.totalCrawls).isEqualTo(3)
        assertThat(run.runStatus).isEqualTo(FsCrawlOrchestratorRunStatus.COMPLETED)

        // Reload the run + query outcomes via their own repository — the
        // orchestrator persists per-crawl outcomes through the outcome
        // repo (not by navigating the OneToMany collection), so going
        // through the same path matches production semantics.
        val loaded = orchestratorRunRepository.findById(run.id!!).orElseThrow()
        val outcomes = orchestratorOutcomeRepository
            .findByOrchestratorRunIdOrderByStartedAtAsc(run.id!!)
        assertThat(outcomes).hasSize(3)
        assertThat(outcomes.map { it.crawlConfigId })
            .describedAs("each enabled config produced one outcome row")
            .containsExactlyInAnyOrderElementsOf(configIds)
        assertThat(outcomes)
            .describedAs("every crawl ran to COMPLETED")
            .allMatch { it.outcome == FsCrawlOutcomeStatus.SUCCEEDED }
        assertThat(loaded.succeeded).isEqualTo(3)
        assertThat(loaded.failed).isZero()
        assertThat(loaded.skipped).isZero()
        assertThat(loaded.finishedAt).isNotNull
        assertThat(outcomes).allMatch { it.jobExecutionId != null }
        assertThat(outcomes).allMatch { it.elapsedMs != null && it.elapsedMs!! >= 0 }
    }

    @Test
    @DisplayName("poison crawl in the middle → siblings still complete, failure recorded, sweep COMPLETED")
    fun poisonedCrawlDoesNotAbortSiblings() {
        val root = buildFixture()
        val configIds = persistEnabledCrawls(
            root,
            listOf("docs", "analyzed", "metadata-only")
        )

        // The middle crawl ("analyzed") trips a deliberate failure. We
        // route it through the JobBuilder spy so the orchestrator's
        // catch around buildJob/launch records the failure and moves on
        // to the next sibling.
        val poisonConfigId = configIds[1]
        Mockito.doAnswer { invocation ->
            val crawl = invocation.arguments[0] as com.oconeco.spring_search_tempo.base.config.CrawlDefinition
            if (crawl.name == "IT_CONFIG_$poisonConfigId") {
                throw IllegalStateException("simulated poison failure for $poisonConfigId")
            }
            invocation.callRealMethod()
        }.`when`(fsCrawlJobBuilder).buildJob(
            crawl = anyNonNull(),
            forceFullRecrawl = Mockito.anyBoolean(),
            crawlConfigId = anyNullable(),
            freshnessHours = anyNullable(),
            chunkProcessAll = Mockito.anyBoolean()
        )

        val run = fsCrawlOrchestrator.runAllEnabledCrawls(triggeredBy = "it")
        val loaded = orchestratorRunRepository.findById(run.id!!).orElseThrow()
        val outcomes = orchestratorOutcomeRepository
            .findByOrchestratorRunIdOrderByStartedAtAsc(run.id!!)

        assertThat(outcomes).hasSize(3)
        assertThat(loaded.runStatus)
            .describedAs("sweep itself COMPLETED — per-crawl failure does NOT abort the sweep")
            .isEqualTo(FsCrawlOrchestratorRunStatus.COMPLETED)
        assertThat(loaded.succeeded).isEqualTo(2)
        assertThat(loaded.failed).isEqualTo(1)

        val poisonOutcome = outcomes.first { it.crawlConfigId == poisonConfigId }
        assertThat(poisonOutcome.outcome).isEqualTo(FsCrawlOutcomeStatus.FAILED)
        assertThat(poisonOutcome.errorMessage)
            .contains("simulated poison failure")

        val siblingOutcomes = outcomes.filter { it.crawlConfigId != poisonConfigId }
        assertThat(siblingOutcomes).hasSize(2)
        assertThat(siblingOutcomes)
            .describedAs("sibling crawls must still complete successfully")
            .allMatch { it.outcome == FsCrawlOutcomeStatus.SUCCEEDED }
    }

    private fun buildFixture(): Path {
        val fixtureBase = Path.of("build", "tmp", "it-fixtures").toAbsolutePath()
        Files.createDirectories(fixtureBase)
        tmp = Files.createTempDirectory(fixtureBase, "fs-orchestrator-it-")
        return CrawlTreeFixture.build(tmp)
    }

    /**
     * Persist one enabled `CrawlConfig` per relative subdirectory, all
     * locate-only with permissive folder/file matches so the underlying
     * crawl completes in milliseconds. Pattern shape is mirrored from the
     * fixture-tailored ITs that already exercise FsCrawlJobBuilder
     * directly — we don't care to re-test the crawler itself here.
     */
    private fun persistEnabledCrawls(root: Path, relativeStartDirs: List<String>): List<Long> {
        return relativeStartDirs.map { rel ->
            val startPath = root.resolve(rel).toString()
            val config = CrawlConfig().apply {
                this.name = "IT_CONFIG_PLACEHOLDER"  // overwritten below once we know the id
                this.label = "IT $rel"
                this.startPaths = arrayOf(startPath)
                this.maxDepth = 5
                this.enabled = true
                this.followLinks = false
                this.parallel = false
                this.folderPatternsLocate = """[".*"]"""
                this.filePatternsLocate = """[".*"]"""
                this.sourceHost = "it-host"
                this.uri = "tempo:crawl-config:it-host/$rel-${System.nanoTime()}"
            }
            val saved = crawlConfigRepository.save(config)
            // Name needs to be unique AND knowable from the test so the
            // failure-injection spy can match the poison crawl by name.
            saved.name = "IT_CONFIG_${saved.id}"
            crawlConfigRepository.save(saved).id!!
        }
    }

    /**
     * Raw Mockito + Kotlin: `ArgumentMatchers.any()` returns `null`, which
     * trips Kotlin's non-null parameter check. Suppress the resulting null
     * via the unchecked-cast pattern.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T {
        org.mockito.ArgumentMatchers.any<T>()
        return null as T
    }

    /** Matcher form for nullable parameters; `null` is a legal value. */
    private fun <T> anyNullable(): T? {
        org.mockito.ArgumentMatchers.any<T>()
        return null
    }
}
