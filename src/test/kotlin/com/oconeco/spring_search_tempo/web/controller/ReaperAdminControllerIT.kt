package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.ReapedJob
import com.oconeco.spring_search_tempo.base.repos.ReapedJobRepository
import io.restassured.RestAssured
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime

/**
 * Integration test for `/admin/reaper` (issue #74).
 *
 * Seeds a `ReapedJob` row and asserts the admin page renders with the
 * row's key fields visible.
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ReaperAdminControllerIT : BaseIT() {

    @Autowired
    lateinit var reapedJobRepository: ReapedJobRepository

    @Test
    @DisplayName("GET /admin/reaper renders a seeded reaping row")
    fun listsRecentReapings() {
        val now = OffsetDateTime.now()
        val seeded = ReapedJob().apply {
            this.reapedAt = now
            this.jobExecutionId = 99_887_766L
            this.jobName = "reaperIT_jobName_${System.nanoTime()}"
            this.accountId = 73L
            this.originalStartedAt = now.minusMinutes(5)
        }
        reapedJobRepository.save(seeded)

        val body = RestAssured
            .given()
                .accept("text/html")
                .redirects().follow(false)
            .`when`()
                .get("/admin/reaper")
            .then()
                .statusCode(HttpStatus.OK.value())
                .extract().body().asString()

        assert(body.contains("Orphan Reaper")) { "missing page title" }
        assert(body.contains(seeded.jobName!!)) {
            "expected seeded jobName='${seeded.jobName}' in body"
        }
        assert(body.contains("99887766") || body.contains("#99887766")) {
            "expected seeded jobExecutionId in body"
        }
        assert(body.contains("73")) { "expected seeded accountId in body" }
    }

    @Test
    @DisplayName("GET /admin/reaper renders empty-state when nothing is recorded")
    fun rendersEmptyState() {
        // BaseIT's clearAll.sql TRUNCATEs all tables before each test, so
        // reaped_job starts empty here.
        val body = RestAssured
            .given()
                .accept("text/html")
                .redirects().follow(false)
            .`when`()
                .get("/admin/reaper")
            .then()
                .statusCode(HttpStatus.OK.value())
                .extract().body().asString()

        assert(body.contains("No reapings recorded yet")) {
            "expected empty-state copy"
        }
    }
}
