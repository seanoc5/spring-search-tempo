package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternPriority
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.FolderAuditRun
import com.oconeco.spring_search_tempo.base.domain.FolderAuditRunStatus
import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.base.repos.FolderSnapshotRepository
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
 * IT for the folder-audit reconciliation service (issue #138).
 *
 * Runs a real audit over a TempDir fixture so we exercise the production
 * visitor → snapshot repository → reconciliation service path end-to-end,
 * then checks the delta math, severity banding, and per-top-level-path
 * breakdown against `Files.walk()` ground truth.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("FolderAuditReconciliationService — delta math + per-path breakdown (issue #138)")
class FolderAuditReconciliationServiceIT : BaseIT() {

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
    lateinit var reconciliationService: FolderAuditReconciliationService

    @Test
    @DisplayName("reconcile() returns severity GREEN on exact ground-truth match; breakdown lists immediate children")
    fun exactMatchIsGreenAndBreakdownCoversChildren() {
        val fixture = buildFixture()
        wireCrawlConfig(fixture)

        val groundTruth = Files.walk(fixture).use { stream ->
            stream.filter { Files.isDirectory(it) }.count()
        }

        val runId = runAuditAndAwait()
        val run = folderAuditRunRepository.findById(runId).orElseThrow()
        assertThat(run.totalFolders).isEqualTo(groundTruth)

        val result = reconciliationService.reconcile(runId, groundTruth, "find ${fixture} -type d | wc -l")
        assertThat(result.auditTotal).isEqualTo(groundTruth)
        assertThat(result.groundTruthTotal).isEqualTo(groundTruth)
        assertThat(result.delta).isZero()
        assertThat(result.percentDelta).isEqualTo(0.0)
        assertThat(result.severity)
            .isEqualTo(FolderAuditReconciliationService.Severity.GREEN)
        assertThat(result.sourceCommand).isEqualTo("find ${fixture} -type d | wc -l")

        val breakdown = reconciliationService.computeBreakdown(runId)
        assertThat(breakdown.map { it.path })
            .describedAs("breakdown should list the two top-level children of the fixture root")
            .containsExactlyInAnyOrder(
                fixture.resolve("alpha").toString(),
                fixture.resolve("beta").toString(),
            )

        val alpha = breakdown.first { it.path.endsWith("alpha") }
        assertThat(alpha.auditCount)
            .describedAs("alpha + alpha/aa + alpha/ab")
            .isEqualTo(3L)
        assertThat(alpha.foldersWalkedInto).isEqualTo(2L)
        assertThat(alpha.underSkipPattern).isNull()
        assertThat(alpha.isSkipRoot).isFalse()

        val beta = breakdown.first { it.path.endsWith("beta") }
        assertThat(beta.auditCount)
            .describedAs("beta + beta/ba")
            .isEqualTo(2L)
        assertThat(beta.foldersWalkedInto).isEqualTo(1L)
    }

    @Test
    @DisplayName("severity bands: ≤1% → GREEN, ≤5% → YELLOW, >5% → RED")
    fun severityBands() {
        // Use a synthetic run with a totalFolders large enough for the bands
        // to be testable — the live fixture tree is too small (auditTotal=6
        // means the smallest non-zero delta is already ~17%, which lands in
        // RED and skips YELLOW). Reconciliation only reads `totalFolders` off
        // the run; it does not need snapshots for this assertion.
        val syntheticRunId = saveSyntheticRun(totalFolders = 1000L)

        val green = reconciliationService.reconcile(syntheticRunId, 1005L, null)
        assertThat(green.percentDelta)
            .describedAs("0.5%")
            .isEqualTo(0.5)
        assertThat(green.severity)
            .isEqualTo(FolderAuditReconciliationService.Severity.GREEN)
        assertThat(green.delta).isEqualTo(5L)

        val yellow = reconciliationService.reconcile(syntheticRunId, 970L, null)
        assertThat(yellow.percentDelta)
            .describedAs("3.0%, signed delta -30")
            .isEqualTo(3.0)
        assertThat(yellow.delta).isEqualTo(-30L)
        assertThat(yellow.severity)
            .isEqualTo(FolderAuditReconciliationService.Severity.YELLOW)

        val red = reconciliationService.reconcile(syntheticRunId, 800L, null)
        assertThat(red.percentDelta)
            .describedAs("20.0%")
            .isEqualTo(20.0)
        assertThat(red.severity)
            .isEqualTo(FolderAuditReconciliationService.Severity.RED)
        assertThat(red.delta).isEqualTo(-200L)
    }

    @Test
    @DisplayName("blank sourceCommand is normalized to null in result")
    fun blankSourceCommandIsNulled() {
        val fixture = buildFixture()
        wireCrawlConfig(fixture)
        val runId = runAuditAndAwait()
        val total = folderAuditRunRepository.findById(runId).orElseThrow().totalFolders

        val result = reconciliationService.reconcile(runId, total, "   ")
        assertThat(result.sourceCommand).isNull()
    }

    private fun saveSyntheticRun(totalFolders: Long): Long {
        val run = FolderAuditRun().apply {
            started = java.time.OffsetDateTime.now()
            finished = java.time.OffsetDateTime.now()
            sourceRef = "synthetic"
            this.totalFolders = totalFolders
            status = FolderAuditRunStatus.COMPLETED
        }
        return folderAuditRunRepository.save(run).id!!
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun buildFixture(): Path {
        val fixture = Files.createDirectory(tempDir.resolve("recon-fixture"))
        Files.createDirectory(fixture.resolve("alpha"))
        Files.createDirectory(fixture.resolve("alpha/aa"))
        Files.createDirectory(fixture.resolve("alpha/ab"))
        Files.createDirectory(fixture.resolve("beta"))
        Files.createDirectory(fixture.resolve("beta/ba"))
        Files.createFile(fixture.resolve("alpha/aa/x.txt"))
        return fixture
    }

    private fun wireCrawlConfig(fixture: Path) {
        val crawl = CrawlDefinition().apply {
            name = "test-reconciliation"
            enabled = true
            startPaths = listOf(fixture.toString())
        }
        val effective = EffectivePatterns(
            folderPatterns = PatternSet(),
            filePatterns = PatternSet(),
            folderPatternPriority = PatternPriority(),
            filePatternPriority = PatternPriority(),
        )
        Mockito.doReturn(listOf(crawl)).`when`(crawlConfigService).getEnabledCrawls()
        Mockito.doReturn(effective).`when`(crawlConfigService).getEffectivePatterns(crawl)
    }

    private fun runAuditAndAwait(): Long {
        val runId = folderAuditService.startFilesystemAuditRun()
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val run = folderAuditRunRepository.findById(runId).orElse(null)
            if (run != null && run.status != FolderAuditRunStatus.RUNNING) {
                assertThat(run.status).isEqualTo(FolderAuditRunStatus.COMPLETED)
                return runId
            }
            Thread.sleep(100)
        }
        throw AssertionError("Audit run #$runId did not complete in 30s")
    }
}
