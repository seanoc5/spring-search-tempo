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
 * Integration tests for the Crawl Configurations page.
 *
 * Tests verify:
 * - Crawl configs list page loads successfully
 * - Job runs list page loads
 * - Add config page loads
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class CrawlConfigIntegrationTest : BaseIT() {

    @Test
    fun `crawl configs list page loads successfully`() {
        RestAssured
            .given()
                .accept(ContentType.HTML)
            .`when`()
                .get("/crawlConfigs")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Crawl Config"))
    }

    @Test
    fun `crawl configs add page loads successfully`() {
        RestAssured
            .given()
                .accept(ContentType.HTML)
            .`when`()
                .get("/crawlConfigs/add")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Add Crawl Config"))
    }

    @Test
    fun `job runs list page loads successfully`() {
        RestAssured
            .given()
                .accept(ContentType.HTML)
            .`when`()
                .get("/crawlConfigs/jobRuns")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Job Run"))
    }
}
