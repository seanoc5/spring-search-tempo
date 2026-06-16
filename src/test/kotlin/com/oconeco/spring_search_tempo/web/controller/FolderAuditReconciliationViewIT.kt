package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.FolderAuditRun
import com.oconeco.spring_search_tempo.base.domain.FolderAuditRunStatus
import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime

/**
 * Light smoke test that the reconciliation form + result block render on
 * `/admin/folder-audit/runs/{id}` (issue #138). Guards against the Thymeleaf
 * SpEL pitfalls called out in CLAUDE.md (null nav, ternary syntax, reserved
 * names). Persists a hand-rolled FolderAuditRun row so the test doesn't need
 * to run a full audit — the reconciliation view only depends on the run's
 * `totalFolders` field and any snapshots present (none required for the
 * delta block).
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("Folder audit detail view — reconciliation form + delta block")
class FolderAuditReconciliationViewIT : BaseIT() {

    @Autowired
    lateinit var folderAuditRunRepository: FolderAuditRunRepository

    @Test
    fun `detail view renders reconciliation form (no submission)`() {
        val runId = saveRun(totalFolders = 1000L)

        RestAssured
            .given()
                .accept(ContentType.HTML)
            .`when`()
                .get("/admin/folder-audit/runs/$runId")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Reconciliation"))
                .body(containsString("Ground-truth count"))
                .body(containsString("Per-top-level-path breakdown"))
    }

    @Test
    fun `detail view renders delta block when ground-truth query param supplied`() {
        val runId = saveRun(totalFolders = 1000L)

        RestAssured
            .given()
                .accept(ContentType.HTML)
            .`when`()
                .get("/admin/folder-audit/runs/$runId?groundTruthCount=970&sourceCommand=find+%2F+-type+d+%7C+wc+-l")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Delta vs."))
                .body(containsString("3.00%"))
                .body(containsString("find / -type d | wc -l"))
                .body(containsString("bg-warning"))
    }

    private fun saveRun(totalFolders: Long): Long {
        val run = FolderAuditRun().apply {
            started = OffsetDateTime.now()
            finished = OffsetDateTime.now()
            sourceRef = "view-test"
            this.totalFolders = totalFolders
            status = FolderAuditRunStatus.COMPLETED
        }
        return folderAuditRunRepository.save(run).id!!
    }
}
