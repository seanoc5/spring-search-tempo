package com.oconeco.spring_search_tempo.web.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.config.CrawlDefaults
import com.oconeco.spring_search_tempo.base.config.CrawlDefinition
import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.config.PatternPriority
import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.FolderAuditRunStatus
import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.base.repos.FolderSnapshotRepository
import com.oconeco.spring_search_tempo.base.service.CrawlConfigService
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration test for the folder-audit REST surface (issue #103).
 *
 * Replaces [CrawlConfigService] with a Mockito bean so the audit walks
 * a controlled `@TempDir` fixture instead of the host filesystem. Then
 * POSTs `/api/audit/folders/run`, polls until the run reaches a terminal
 * status, and asserts the persisted snapshot count matches `Files.walk()`
 * ground truth — the reconciliation guarantee called out in the issue.
 *
 * Mockito-kotlin isn't on the test classpath; raw Mockito is used.
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("FolderAuditResource — end-to-end via REST (issue #103)")
class FolderAuditResourceIT : BaseIT() {

    @TempDir
    lateinit var tempDir: Path

    @MockitoSpyBean
    lateinit var crawlConfigService: CrawlConfigService

    @Autowired
    lateinit var folderAuditRunRepository: FolderAuditRunRepository

    @Autowired
    lateinit var folderSnapshotRepository: FolderSnapshotRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("POST /api/audit/folders/run starts a run that reconciles to Files.walk() on a fixture tree")
    fun reconcilesAgainstFilesWalk() {
        // ── Fixture ────────────────────────────────────────────────────
        val fixture = Files.createDirectory(tempDir.resolve("fixture-it"))
        Files.createDirectory(fixture.resolve("alpha"))
        Files.createDirectory(fixture.resolve("alpha/aa"))
        Files.createDirectory(fixture.resolve("alpha/ab"))
        Files.createDirectory(fixture.resolve("beta"))
        Files.createDirectory(fixture.resolve("beta/ba"))
        Files.createFile(fixture.resolve("alpha/aa/x.txt"))

        val groundTruthFolderCount = Files.walk(fixture).use { stream ->
            stream.filter { Files.isDirectory(it) }.count()
        }

        // ── Wire crawl config service ──────────────────────────────────
        val crawl = CrawlDefinition().apply {
            name = "test-audit"
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

        // ── Trigger ────────────────────────────────────────────────────
        val response = RestAssured
            .given()
                .contentType(ContentType.JSON)
            .`when`()
                .post("/api/audit/folders/run")
            .then()
                .statusCode(HttpStatus.ACCEPTED.value())
                .extract().body().asString()

        val runId = objectMapper.readTree(response).get("runId").asLong()
        assertThat(runId).isPositive()

        // ── Wait for completion ────────────────────────────────────────
        val deadline = System.currentTimeMillis() + 30_000
        var status: FolderAuditRunStatus = FolderAuditRunStatus.RUNNING
        while (System.currentTimeMillis() < deadline) {
            val run = folderAuditRunRepository.findById(runId).orElse(null)
            if (run != null && run.status != FolderAuditRunStatus.RUNNING) {
                status = run.status
                break
            }
            Thread.sleep(100)
        }

        // ── Assertions ─────────────────────────────────────────────────
        assertThat(status).isEqualTo(FolderAuditRunStatus.COMPLETED)

        val run = folderAuditRunRepository.findById(runId).orElseThrow()
        assertThat(run.totalFolders).isEqualTo(groundTruthFolderCount)
        assertThat(run.skipSubtreeCount).isZero()
        assertThat(run.hiddenGemCount).isZero()

        val snapshotCount = folderSnapshotRepository.countByAuditRunId(runId)
        assertThat(snapshotCount)
            .describedAs("one folder_snapshot per directory walked")
            .isEqualTo(groundTruthFolderCount)

        // ── GET /runs surfaces the new run ─────────────────────────────
        val listBody = RestAssured
            .given().accept(ContentType.JSON)
            .`when`().get("/api/audit/folders/runs")
            .then().statusCode(HttpStatus.OK.value())
            .extract().body().asString()
        assertThat(listBody).contains("\"id\":$runId")

        // ── GET /runs/{id} returns the COMPLETED run ───────────────────
        val detailBody = RestAssured
            .given().accept(ContentType.JSON)
            .`when`().get("/api/audit/folders/runs/$runId")
            .then().statusCode(HttpStatus.OK.value())
            .extract().body().asString()
        assertThat(detailBody).contains("\"status\":\"COMPLETED\"")
    }
}
