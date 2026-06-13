package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternPriority
import com.oconeco.spring_search_tempo.base.config.PatternSet
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
import org.springframework.test.context.TestPropertySource
import java.nio.file.Files
import java.nio.file.Path

/**
 * IT for snapshot rotation (issue #105, acceptance criterion `d`).
 *
 * Runs N+1 (=3) audits with `app.audit.retain-runs=2`, asserts only the
 * latest 2 survive in both `folder_audit_run` and `folder_snapshot`.
 *
 * The fixture tree is a fresh `@TempDir` so the visitor has predictable
 * folder counts. We spy `CrawlConfigService` to point the audit at the
 * fixture instead of the host filesystem (same trick as FolderAuditResourceIT).
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@TestPropertySource(properties = ["app.audit.retain-runs=2"])
@DisplayName("Folder audit retention — N+1 runs, only latest N survive (issue #105)")
class FolderAuditRetentionIT : BaseIT() {

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

    @Test
    @DisplayName("after 3 runs with retain-runs=2, only the latest 2 runs and their snapshots remain")
    fun rotationKeepsLatestN() {
        // ── Fixture ────────────────────────────────────────────────────
        val fixture = Files.createDirectory(tempDir.resolve("retention-fixture"))
        Files.createDirectory(fixture.resolve("alpha"))
        Files.createDirectory(fixture.resolve("beta"))

        val crawl = CrawlDefinition().apply {
            name = "test-retention"
            enabled = true
            startPaths = listOf(fixture.toString())
        }
        val effective = EffectivePatterns(
            folderPatterns = PatternSet(),
            filePatterns = PatternSet(),
            folderPatternPriority = PatternPriority(),
            filePatternPriority = PatternPriority()
        )
        Mockito.doReturn(listOf(crawl)).`when`(crawlConfigService).getEnabledCrawls()
        Mockito.doReturn(effective).`when`(crawlConfigService).getEffectivePatterns(crawl)

        // ── 3 sequential audits ────────────────────────────────────────
        val runIds = (1..3).map {
            val runId = folderAuditService.startFilesystemAuditRun()
            awaitCompletion(runId)
            runId
        }
        assertThat(runIds).hasSize(3)

        // ── Assert only the latest 2 runs survived ─────────────────────
        val survivors = folderAuditRunRepository.findAll().map { it.id!! }
        assertThat(survivors)
            .describedAs("only latest 2 runs should remain (retain-runs=2)")
            .hasSize(2)
            .containsExactlyInAnyOrder(runIds[1], runIds[2])
            .doesNotContain(runIds[0])

        // ── Snapshots for surviving runs are still there ───────────────
        for (id in runIds.drop(1)) {
            assertThat(folderSnapshotRepository.countByAuditRunId(id))
                .describedAs("snapshots for surviving run $id")
                .isPositive()
        }

        // ── Snapshots for the dropped run are gone ─────────────────────
        assertThat(folderSnapshotRepository.countByAuditRunId(runIds[0]))
            .describedAs("snapshots for dropped run ${runIds[0]} must be zero")
            .isZero()
    }

    private fun awaitCompletion(runId: Long, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val run = folderAuditRunRepository.findById(runId).orElse(null)
            if (run != null && run.status != FolderAuditRunStatus.RUNNING) {
                assertThat(run.status).isEqualTo(FolderAuditRunStatus.COMPLETED)
                return
            }
            Thread.sleep(100)
        }
        error("Audit runId=$runId did not complete within ${timeoutMs}ms")
    }
}
