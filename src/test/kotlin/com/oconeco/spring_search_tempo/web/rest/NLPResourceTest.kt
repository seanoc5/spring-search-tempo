package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
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
        RestAssured
            .given()
                .accept(ContentType.JSON)
            .`when`()
                .post("/api/nlp/process")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("jobName", equalTo("nlpProcessingJob"))
                .body("status", notNullValue())
                .body("message", notNullValue())
                .body("executionId", notNullValue())
    }
}
