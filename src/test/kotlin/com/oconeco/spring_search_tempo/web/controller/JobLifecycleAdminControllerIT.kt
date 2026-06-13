package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.JobLifecycleEvent
import com.oconeco.spring_search_tempo.base.domain.JobLifecycleEventType
import com.oconeco.spring_search_tempo.base.repos.JobLifecycleEventRepository
import io.restassured.RestAssured
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime

/**
 * Integration test for `/admin/job-lifecycle` (issue #75).
 *
 * Seeds one REAPED event and one SHUTDOWN event and asserts the admin
 * page renders both with their distinguishing badges and the legacy
 * `/admin/reaper` URL still resolves via 301 redirect.
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class JobLifecycleAdminControllerIT : BaseIT() {

    @Autowired
    lateinit var jobLifecycleEventRepository: JobLifecycleEventRepository

    @Test
    @DisplayName("GET /admin/job-lifecycle renders reap + shutdown events side-by-side")
    fun listsBothEventTypes() {
        val now = OffsetDateTime.now()

        val reaperJobName = "lifecycleIT_reap_${System.nanoTime()}"
        val reapEvent = JobLifecycleEvent().apply {
            this.eventTime = now.minusSeconds(10)
            this.eventType = JobLifecycleEventType.REAPED
            this.actionTaken = "reaped"
            this.jobExecutionId = 99_887_766L
            this.jobName = reaperJobName
            this.accountId = 73L
            this.originalStartedAt = now.minusMinutes(5)
            this.details = "Orphan reaper: advisory lock acquired"
        }
        jobLifecycleEventRepository.save(reapEvent)

        val shutdownJobName = "lifecycleIT_shutdown_${System.nanoTime()}"
        val shutdownEvent = JobLifecycleEvent().apply {
            this.eventTime = now
            this.eventType = JobLifecycleEventType.SHUTDOWN
            this.actionTaken = "stopped"
            this.jobExecutionId = 99_887_777L
            this.jobName = shutdownJobName
            this.accountId = 42L
            this.originalStartedAt = now.minusMinutes(1)
            this.details = "App shutdown: job did not complete before JVM stopped"
        }
        jobLifecycleEventRepository.save(shutdownEvent)

        val body = RestAssured
            .given()
                .accept("text/html")
                .redirects().follow(false)
            .`when`()
                .get("/admin/job-lifecycle")
            .then()
                .statusCode(HttpStatus.OK.value())
                .extract().body().asString()

        assert(body.contains("Job Lifecycle Events")) { "missing page title" }
        assert(body.contains(reaperJobName)) { "expected reap event jobName" }
        assert(body.contains(shutdownJobName)) { "expected shutdown event jobName" }
        assert(body.contains("reaped")) { "expected 'reaped' badge text" }
        assert(body.contains("shutdown")) { "expected 'shutdown' badge text" }
        assert(body.contains("stopped")) { "expected 'stopped' action text" }
        assert(body.contains("73")) { "expected reap accountId in body" }
        assert(body.contains("42")) { "expected shutdown accountId in body" }
    }

    @Test
    @DisplayName("GET /admin/job-lifecycle renders empty-state when nothing is recorded")
    fun rendersEmptyState() {
        val body = RestAssured
            .given()
                .accept("text/html")
                .redirects().follow(false)
            .`when`()
                .get("/admin/job-lifecycle")
            .then()
                .statusCode(HttpStatus.OK.value())
                .extract().body().asString()

        assert(body.contains("No lifecycle events recorded yet")) {
            "expected empty-state copy"
        }
    }

    @Test
    @DisplayName("Legacy /admin/reaper redirects to /admin/job-lifecycle")
    fun legacyReaperPathRedirects() {
        val response = RestAssured
            .given()
                .accept("text/html")
                .redirects().follow(false)
            .`when`()
                .get("/admin/reaper")
            .then()
                .extract()

        val status = response.statusCode()
        assert(status == HttpStatus.MOVED_PERMANENTLY.value()) {
            "expected 301 redirect from /admin/reaper, got $status"
        }
        val location = response.header("Location") ?: ""
        assert(location.endsWith("/admin/job-lifecycle")) {
            "expected redirect target /admin/job-lifecycle, got '$location'"
        }
    }
}
