package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternPriority
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FolderAuditRunStatus
import com.oconeco.spring_search_tempo.base.domain.HiddenGemResolutionKind
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.base.repos.FolderSnapshotRepository
import com.oconeco.spring_search_tempo.base.repos.HiddenGemResolutionRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests for issue #104 — durable hidden-gem resolutions
 * (dismiss + reclassify) layered on top of the issue #103 folder audit.
 *
 * The fixture mimics the canonical "hidden gem" shape: a SKIP folder
 * (`node_modules`) containing a child folder (`internal-tool`) whose
 * name matches an INDEX pattern. Two end-to-end paths are exercised:
 *
 *  1. **Dismiss → re-run → gone.** Confirms the resolution row survives
 *     snapshot rotation and that the `NOT EXISTS` filter in the
 *     hidden-gem query honors it.
 *
 *  2. **Reclassify to INDEX.** Confirms the resolution row lands AND
 *     a `CrawlConfig` row was created/updated with the path appended
 *     to its `folderPatternsIndex` JSON array.
 *
 * Mockito-kotlin isn't on the classpath; we use raw `Mockito.doReturn`
 * to script the spy bean, matching the convention from
 * `FolderAuditResourceIT`.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("Hidden-gem resolutions (issue #104)")
class HiddenGemResolutionIT : BaseIT() {

    @TempDir
    lateinit var tempDir: Path

    @MockitoSpyBean
    lateinit var crawlConfigService: CrawlConfigService

    @Autowired
    lateinit var folderAuditService: FolderAuditService

    @Autowired
    lateinit var folderAuditRunRepository: FolderAuditRunRepository

    @Autowired
    lateinit var folderSnapshotRepository: FolderSnapshotRepository

    @Autowired
    lateinit var hiddenGemResolutionRepository: HiddenGemResolutionRepository

    @Autowired
    lateinit var crawlConfigRepository: CrawlConfigRepository

    @Autowired
    lateinit var hiddenGemResolutionService: HiddenGemResolutionService

    @Test
    @DisplayName("Dismissing a hidden-gem candidate keeps it out of the list on the next audit run")
    fun dismissHidesCandidateOnRerun() {
        val (fixture, hiddenGemPath) = createHiddenGemFixture()
        wireMockCrawl(fixture)

        // ── First audit run: candidate appears ─────────────────────────
        val runId1 = runAuditAndAwait()
        val run1 = folderAuditRunRepository.findById(runId1).orElseThrow()
        val sourceRef = run1.sourceRef ?: "(unknown)"

        val candidates1 = folderSnapshotRepository.findUnresolvedHiddenGems(runId1, sourceRef)
        assertThat(candidates1.map { it.path })
            .describedAs("first run should surface the node_modules/internal-tool hidden-gem")
            .contains(hiddenGemPath)

        // ── Dismiss ────────────────────────────────────────────────────
        hiddenGemResolutionService.dismiss(runId1, hiddenGemPath, "test-user")
        val resolution = hiddenGemResolutionRepository
            .findBySourceRefAndPath(sourceRef, hiddenGemPath)
        assertThat(resolution).isNotNull
        assertThat(resolution!!.resolution).isEqualTo(HiddenGemResolutionKind.DISMISSED)

        // ── Second audit run: candidate suppressed ─────────────────────
        val runId2 = runAuditAndAwait()
        val candidates2 = folderSnapshotRepository.findUnresolvedHiddenGems(runId2, sourceRef)
        assertThat(candidates2.map { it.path })
            .describedAs("dismissed hidden-gem must not reappear after re-run")
            .doesNotContain(hiddenGemPath)
    }

    @Test
    @DisplayName("Reclassifying to INDEX writes a CrawlConfig rule and a PROMOTED_TO_INDEX resolution")
    fun reclassifyAddsCrawlConfigRuleAndResolution() {
        val (fixture, hiddenGemPath) = createHiddenGemFixture()
        wireMockCrawl(fixture)

        val runId = runAuditAndAwait()
        val run = folderAuditRunRepository.findById(runId).orElseThrow()
        val sourceRef = run.sourceRef ?: "(unknown)"

        val crawlConfigsBefore = crawlConfigRepository.findAll().size

        hiddenGemResolutionService.reclassify(
            runId, hiddenGemPath, AnalysisStatus.INDEX, "test-user"
        )

        // Resolution row
        val resolution = hiddenGemResolutionRepository
            .findBySourceRefAndPath(sourceRef, hiddenGemPath)
        assertThat(resolution).isNotNull
        assertThat(resolution!!.resolution)
            .isEqualTo(HiddenGemResolutionKind.PROMOTED_TO_INDEX)

        // CrawlConfig rule
        val crawlConfigsAfter = crawlConfigRepository.findAll()
        assertThat(crawlConfigsAfter.size)
            .describedAs("reclassify must create a CrawlConfig if none matches the source_ref")
            .isGreaterThan(crawlConfigsBefore)

        val touchedConfig = crawlConfigsAfter
            .firstOrNull { it.folderPatternsIndex?.contains(hiddenGemPath) == true }
        assertThat(touchedConfig)
            .describedAs("a CrawlConfig should now have the hidden-gem path in its folderPatternsIndex JSON")
            .isNotNull
    }

    private fun createHiddenGemFixture(): Pair<Path, String> {
        val fixture = Files.createDirectory(tempDir.resolve("fixture-it"))
        Files.createDirectory(fixture.resolve("docs"))
        Files.createDirectory(fixture.resolve("node_modules"))
        val nodeModules = fixture.resolve("node_modules")
        Files.createDirectory(nodeModules.resolve("some-lib"))
        val internalTool = nodeModules.resolve("internal-tool")
        Files.createDirectory(internalTool)
        Files.createFile(internalTool.resolve("README.md"))
        return fixture to internalTool.toString()
    }

    private fun wireMockCrawl(fixture: Path) {
        val crawl = CrawlDefinition().apply {
            name = "test-audit-104"
            enabled = true
            startPaths = listOf(fixture.toString())
        }
        // SKIP everything under node_modules; INDEX anything matching
        // .*/internal-tool($|/.*). The audit's synthetic-child probe
        // identifies the node_modules folder itself as the SKIP root.
        val effective = EffectivePatterns(
            folderPatterns = PatternSet(
                skip = listOf(".*/node_modules/.*"),
                locate = emptyList(),
                index = listOf(".*/internal-tool", ".*/internal-tool/.*"),
                analyze = emptyList(),
                semantic = emptyList()
            ),
            filePatterns = PatternSet(),
            folderPatternPriority = PatternPriority(),
            filePatternPriority = PatternPriority()
        )
        Mockito.doReturn(listOf(crawl)).`when`(crawlConfigService).getEnabledCrawls()
        Mockito.doReturn(effective).`when`(crawlConfigService).getEffectivePatterns(crawl)
    }

    private fun runAuditAndAwait(): Long {
        // The dedup check in FolderAuditService reads from JobExplorer,
        // which can briefly still show the previous run as STARTED even
        // after our domain row is COMPLETED. Retry a couple of times
        // before failing.
        var runId: Long = -1L
        val launchDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < launchDeadline) {
            try {
                runId = folderAuditService.startFilesystemAuditRun()
                break
            } catch (e: org.springframework.batch.core.repository.JobExecutionAlreadyRunningException) {
                Thread.sleep(100)
            }
        }
        if (runId == -1L) error("Failed to launch audit run within grace window")

        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val run = folderAuditRunRepository.findById(runId).orElse(null)
            if (run != null && run.status != FolderAuditRunStatus.RUNNING) {
                assertThat(run.status).isEqualTo(FolderAuditRunStatus.COMPLETED)
                return runId
            }
            Thread.sleep(100)
        }
        error("Audit run #$runId did not finish in time")
    }
}
