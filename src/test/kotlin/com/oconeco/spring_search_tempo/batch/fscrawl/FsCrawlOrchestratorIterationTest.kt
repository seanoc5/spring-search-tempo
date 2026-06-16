package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.CrawlConfig
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorRun
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorRunStatus
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOutcomeStatus
import com.oconeco.spring_search_tempo.base.domain.FsCrawlOrchestratorOutcome
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.FsCrawlOrchestratorOutcomeRepository
import com.oconeco.spring_search_tempo.base.repos.FsCrawlOrchestratorRunRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigConverter
import com.oconeco.spring_search_tempo.base.service.CrawlConfigMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.batch.core.repository.JobRepository
import java.util.Optional

/**
 * Unit tests for `FsCrawlOrchestrator` iteration logic (issue #139, AC #f).
 *
 * Mocks `CrawlConfigRepository`, the persistence layer, and leaves the
 * mapper/converter/job-builder as default Mockito mocks (returning null
 * from every call). The orchestrator's per-crawl try/catch translates
 * those nulls (or the downstream NPE on `syncLauncher.run(null, ...)`)
 * into FAILED outcomes — which is exactly the failure-isolation
 * behaviour this test is verifying. The IT covers the happy path
 * against a real Spring Batch JobRepository.
 */
@DisplayName("FsCrawlOrchestrator iteration (issue #139)")
class FsCrawlOrchestratorIterationTest {

    private lateinit var crawlConfigRepository: CrawlConfigRepository
    private lateinit var crawlConfigMapper: CrawlConfigMapper
    private lateinit var crawlConfigConverter: CrawlConfigConverter
    private lateinit var fsCrawlJobBuilder: FsCrawlJobBuilder
    private lateinit var orchestratorRunRepository: FsCrawlOrchestratorRunRepository
    private lateinit var orchestratorOutcomeRepository: FsCrawlOrchestratorOutcomeRepository
    private lateinit var jobRepository: JobRepository

    private lateinit var orchestrator: FsCrawlOrchestrator

    private var nextRunId = 1L
    private var nextOutcomeId = 1L
    private val savedRuns = mutableMapOf<Long, FsCrawlOrchestratorRun>()
    private val savedOutcomes = mutableListOf<FsCrawlOrchestratorOutcome>()

    @BeforeEach
    fun setUp() {
        crawlConfigRepository = mock(CrawlConfigRepository::class.java)
        crawlConfigMapper = mock(CrawlConfigMapper::class.java)
        crawlConfigConverter = mock(CrawlConfigConverter::class.java)
        fsCrawlJobBuilder = mock(FsCrawlJobBuilder::class.java)
        orchestratorRunRepository = mock(FsCrawlOrchestratorRunRepository::class.java)
        orchestratorOutcomeRepository = mock(FsCrawlOrchestratorOutcomeRepository::class.java)
        jobRepository = mock(JobRepository::class.java)

        `when`(orchestratorOutcomeRepository.save(anyNonNull<FsCrawlOrchestratorOutcome>()))
            .thenAnswer { invocation ->
                val o = invocation.getArgument<FsCrawlOrchestratorOutcome>(0)
                if (o.id == null) o.id = nextOutcomeId++
                savedOutcomes.add(o)
                o
            }
        `when`(orchestratorRunRepository.save(anyNonNull<FsCrawlOrchestratorRun>()))
            .thenAnswer { invocation ->
                val run = invocation.getArgument<FsCrawlOrchestratorRun>(0)
                if (run.id == null) run.id = nextRunId++
                savedRuns[run.id!!] = run
                run
            }
        `when`(orchestratorRunRepository.findById(anyLong()))
            .thenAnswer { invocation ->
                val id = invocation.getArgument<Long>(0)
                Optional.ofNullable(savedRuns[id])
            }

        orchestrator = FsCrawlOrchestrator(
            crawlConfigRepository = crawlConfigRepository,
            crawlConfigMapper = crawlConfigMapper,
            crawlConfigConverter = crawlConfigConverter,
            fsCrawlJobBuilder = fsCrawlJobBuilder,
            orchestratorRunRepository = orchestratorRunRepository,
            orchestratorOutcomeRepository = orchestratorOutcomeRepository,
            jobRepository = jobRepository
        )
    }

    @Test
    @DisplayName("no enabled crawls → run is COMPLETED with totalCrawls=0")
    fun emptyEnabledCrawls() {
        `when`(crawlConfigRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(emptyList())

        val run = orchestrator.runAllEnabledCrawls(triggeredBy = "test")

        assertThat(run.runStatus).isEqualTo(FsCrawlOrchestratorRunStatus.COMPLETED)
        assertThat(run.totalCrawls).isZero()
        assertThat(run.succeeded).isZero()
        assertThat(run.failed).isZero()
        // The repo was the only collaborator that should have been touched
        // for enumeration; no per-crawl plumbing was reached.
        verify(crawlConfigRepository, atLeastOnce()).findByEnabledTrueOrderByIdAsc()
    }

    @Test
    @DisplayName("every crawl fails to build → all 3 siblings still iterated; sweep COMPLETED with failed=3")
    fun perCrawlFailureDoesNotAbortSiblings() {
        val configs = listOf(
            crawlConfig(id = 10, name = "A"),
            crawlConfig(id = 20, name = "B"),
            crawlConfig(id = 30, name = "C")
        )
        `when`(crawlConfigRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(configs)

        // Mapper, converter, jobBuilder are default mocks — every call
        // returns null. The orchestrator's catch block converts the
        // downstream NPE into a FAILED outcome row. The contract under
        // test is "siblings keep iterating after a per-crawl failure."

        val run = orchestrator.runAllEnabledCrawls(triggeredBy = "test")

        // All 3 outcomes captured — siblings did NOT abort.
        assertThat(savedOutcomes).hasSize(3)
        assertThat(savedOutcomes.map { it.crawlConfigName })
            .describedAs("iteration must mirror id-ascending repo order: A, B, C")
            .containsExactly("A", "B", "C")
        assertThat(savedOutcomes).allMatch { it.outcome == FsCrawlOutcomeStatus.FAILED }

        // Sweep aggregate: COMPLETED with all 3 failed.
        assertThat(run.runStatus)
            .describedAs("sweep itself COMPLETED — per-crawl failures don't fail the sweep")
            .isEqualTo(FsCrawlOrchestratorRunStatus.COMPLETED)
        assertThat(run.totalCrawls).isEqualTo(3)
        assertThat(run.failed).isEqualTo(3)
        assertThat(run.succeeded).isZero()
    }

    @Test
    @DisplayName("ordering mirrors the repository's id-ascending order (the documented contract)")
    fun iterationOrderMirrorsRepositoryOrder() {
        // Names are chosen alphabetically *out* of id order — that way
        // any test where the orchestrator accidentally re-sorted by name
        // would surface here. The repo derived-query method's name
        // (findByEnabledTrueOrderByIdAsc) pins the order; this asserts
        // the orchestrator doesn't override it.
        val configs = listOf(
            crawlConfig(id = 5, name = "Z_first"),
            crawlConfig(id = 7, name = "A_middle"),
            crawlConfig(id = 9, name = "M_last")
        )
        `when`(crawlConfigRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(configs)

        val run = orchestrator.runAllEnabledCrawls(triggeredBy = "test")

        assertThat(savedOutcomes.map { it.crawlConfigName })
            .containsExactly("Z_first", "A_middle", "M_last")
        verify(crawlConfigRepository, atLeast(1)).findByEnabledTrueOrderByIdAsc()
    }

    private fun crawlConfig(id: Long, name: String): CrawlConfig =
        CrawlConfig().apply {
            this.id = id
            this.name = name
            this.label = name
            this.enabled = true
            this.uri = "tempo:crawl-config:test/$name"
        }

    /**
     * Raw Mockito + Kotlin: `ArgumentMatchers.any()` returns `null`, which
     * trips Kotlin's non-null parameter check at the call site. Suppress
     * the resulting null via the unchecked-cast pattern — matches the
     * convention already used in `RecentCrawlSkipCheckerOwnershipTest`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T {
        org.mockito.ArgumentMatchers.any<T>()
        return null as T
    }
}
