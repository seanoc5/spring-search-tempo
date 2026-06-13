package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus

/**
 * Integration tests for NLPResource REST API.
 *
 * Tests:
 * - GET /api/nlp/status returns NLP status info
 * - POST /api/nlp/process triggers NLP processing job
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class NLPResourceTest : BaseIT() {

    @Autowired
    lateinit var jobExplorer: JobExplorer

    @Test
    fun `GET nlp status should return enabled status`() {
        RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .get("/api/nlp/status")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("enabled", equalTo(true))
                .body("autoTriggerEnabled", equalTo(true))
                .body("message", notNullValue())
    }

    @Test
    fun `POST nlp process should trigger NLP job and return response`() {
        // Note: Job will complete immediately since there are no chunks to process
        // But the endpoint should still return a valid response
        val executionId = RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .post("/api/nlp/process")
            .then()
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("jobName", equalTo("nlpProcessingJob"))
                .body("status", notNullValue())
                .body("message", notNullValue())
                .body("executionId", notNullValue())
            .extract().path<Number>("executionId").toLong()

        // The production JobLauncher is async (BatchTaskExecutorConfig), so the
        // launched batch job runs on a background thread. Wait for it to leave
        // RUNNING/STARTED — otherwise the next test's @Sql TRUNCATE-CASCADE in
        // BaseIT can race with the job's reads/writes against content_chunk and
        // batch tables (issue #106).
        waitForJobToSettle(executionId)
    }

    private fun waitForJobToSettle(executionId: Long, timeoutMillis: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val status = jobExplorer.getJobExecution(executionId)?.status
            if (status != null && status != BatchStatus.STARTING && status != BatchStatus.STARTED) {
                return
            }
            Thread.sleep(50)
        }
        error("NLP job execution $executionId did not finish within ${timeoutMillis}ms")
    }
}
