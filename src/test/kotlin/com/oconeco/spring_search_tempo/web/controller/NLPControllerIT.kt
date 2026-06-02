package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import io.restassured.RestAssured
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus

/**
 * Integration test for the NLP coverage admin page (issue #37).
 *
 * Verifies the page renders, surfaces the expected operator-facing widgets, and
 * that the JSON status endpoint reports the new fields populated by
 * `NLPStatusViewService`.
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class NLPControllerIT : BaseIT() {

    @Test
    fun `GET nlp status HTML page renders coverage dashboard`() {
        val body = RestAssured
            .given()
                .accept("text/html")
                .redirects().follow(false)
            .`when`()
                .get("/nlp/status")
            .then()
                .statusCode(HttpStatus.OK.value())
                .extract().body().asString()

        // Each section in the template should be present in the rendered HTML.
        assert(body.contains("NLP Coverage")) { "missing page title" }
        assert(body.contains("Chunk coverage")) { "missing coverage card" }
        assert(body.contains("Chunks at INDEX level")) { "missing INDEX breakdown" }
        assert(body.contains("Chunks at ANALYZE level")) { "missing ANALYZE breakdown" }
        assert(body.contains("Auto-trigger")) { "missing auto-trigger toggle indicator" }
        assert(body.contains("Run NLP now")) { "missing manual run button" }
        assert(body.contains("Re-process a single chunk")) { "missing per-chunk reprocess form" }
        assert(body.contains("Recent NLP jobs")) { "missing recent jobs table" }
    }

    @Test
    fun `GET api nlp status returns coverage counters`() {
        RestAssured
            .given()
                .accept("application/json")
            .`when`()
                .get("/api/nlp/status")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("totalChunks", org.hamcrest.Matchers.notNullValue())
                .body("nlpProcessedCount", org.hamcrest.Matchers.notNullValue())
                .body("coveragePercent", org.hamcrest.Matchers.notNullValue())
                .body("nlpPendingAtAnalyzeLevel", org.hamcrest.Matchers.notNullValue())
    }

    @Test
    fun `POST nlp process from UI redirects back to status page by default`() {
        val location = RestAssured
            .given()
                .accept("text/html")
                .redirects().follow(false)
            .`when`()
                .post("/nlp/process")
            .then()
                .statusCode(HttpStatus.FOUND.value())
                .extract().header("Location")

        assert(location != null && location.endsWith("/nlp/status")) {
            "expected redirect to /nlp/status, got $location"
        }
    }

    @Test
    fun `POST nlp process reprocess chunk for missing id reports error`() {
        // Chunk ID 0 will never exist (IDs are generated starting at 1) so the
        // service returns false and the flash error is set. Just verify the
        // redirect target — the flash attribute itself rides on the session.
        val location = RestAssured
            .given()
                .accept("text/html")
                .redirects().follow(false)
            .`when`()
                .post("/nlp/process/chunk/{id}", 999_999_999L)
            .then()
                .statusCode(HttpStatus.FOUND.value())
                .extract().header("Location")

        assert(location != null && location.endsWith("/nlp/status")) {
            "expected redirect to /nlp/status, got $location"
        }
    }
}
