package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus

/**
 * Integration tests for the dashboard (home page).
 *
 * Tests verify:
 * - Dashboard loads successfully with statistics
 * - HTMX endpoints for folders and files work
 * - Stats refresh endpoint works
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class DashboardIntegrationTest : BaseIT() {

    @Test
    fun `dashboard page loads successfully`() {
        RestAssured
            .given()
                .accept(ContentType.HTML)
            .`when`()
                .get("/")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Dashboard"))
                .body(containsString("Files Indexed"))
                .body(containsString("Folders Scanned"))
                .body(containsString("Content Chunks"))
    }

    @Test
    fun `dashboard stats refresh endpoint works`() {
        RestAssured
            .given()
                .accept(ContentType.HTML)
                .header("HX-Request", "true")
            .`when`()
                .get("/dashboard/stats")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Files Indexed"))
    }

    @Test
    fun `dashboard folders endpoint works`() {
        RestAssured
            .given()
                .accept(ContentType.HTML)
                .header("HX-Request", "true")
            .`when`()
                .get("/dashboard/folders")
            .then()
                .statusCode(HttpStatus.OK.value())
    }

    @Test
    fun `dashboard files endpoint works`() {
        RestAssured
            .given()
                .accept(ContentType.HTML)
                .header("HX-Request", "true")
            .`when`()
                .get("/dashboard/files")
            .then()
                .statusCode(HttpStatus.OK.value())
    }

    @Test
    fun `dashboard folders endpoint with showSkipped parameter works`() {
        RestAssured
            .given()
                .accept(ContentType.HTML)
                .header("HX-Request", "true")
                .queryParam("showSkipped", "true")
            .`when`()
                .get("/dashboard/folders")
            .then()
                .statusCode(HttpStatus.OK.value())
    }

    @Test
    fun `dashboard files endpoint with pagination works`() {
        RestAssured
            .given()
                .accept(ContentType.HTML)
                .header("HX-Request", "true")
                .queryParam("page", 0)
                .queryParam("size", 5)
            .`when`()
                .get("/dashboard/files")
            .then()
                .statusCode(HttpStatus.OK.value())
    }
}
