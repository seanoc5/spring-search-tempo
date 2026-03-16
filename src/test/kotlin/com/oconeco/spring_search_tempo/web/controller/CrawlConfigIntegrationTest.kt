package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.CrawlMode
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.web.service.CrawlDiscoveryObservationService
import com.oconeco.spring_search_tempo.web.service.RemoteDiscoveryFolderObsIngestItem
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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

    @Autowired
    private lateinit var crawlConfigService: DatabaseCrawlConfigService

    @Autowired
    private lateinit var crawlDiscoveryObservationService: CrawlDiscoveryObservationService

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
    fun `crawl config preset wizard page loads successfully`() {
        RestAssured
            .given()
                .accept(ContentType.HTML)
            .`when`()
                .get("/crawlConfigs/wizard")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Preset Wizard"))
                .body(containsString("POWER_USER"))
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

    @Test
    fun `discovery review page supports pagination`() {
        val host = "mvc-discovery-host"
        val crawlConfigId = createDiscoveryMvcTestCrawlConfig(host)

        crawlDiscoveryObservationService.ingest(
            crawlConfigId = crawlConfigId,
            host = host,
            jobRunId = 1L,
            folders = listOf(
                RemoteDiscoveryFolderObsIngestItem(path = "/data/a", depth = 1, inSkipBranch = true),
                RemoteDiscoveryFolderObsIngestItem(path = "/data/b", depth = 1, inSkipBranch = true),
                RemoteDiscoveryFolderObsIngestItem(path = "/data/c", depth = 1, inSkipBranch = true)
            ),
            fileSamples = emptyList(),
            sampleCap = 50
        )

        RestAssured
            .given()
                .accept(ContentType.HTML)
                .queryParam("host", host)
                .queryParam("limit", 1)
                .queryParam("page", 1)
            .`when`()
                .get("/crawlConfigs/$crawlConfigId/discovery-review")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Discovery Review"))
                .body(containsString("Page 2 of 3"))
                .body(containsString("""name="page" value="1""""))
                .body(containsString("/data/b"))
    }

    private fun createDiscoveryMvcTestCrawlConfig(sourceHost: String): Long {
        val suffix = System.currentTimeMillis()
        return crawlConfigService.create(CrawlConfigDTO().apply {
            name = "DISCOVERY_MVC_IT_$suffix"
            label = "Discovery MVC IT $suffix"
            description = "Integration test crawl config for discovery review page"
            this.sourceHost = sourceHost
            enabled = true
            startPaths = listOf("/data")
            maxDepth = 20
            followLinks = false
            parallel = false
            version = 0L
            crawlMode = CrawlMode.DISCOVERY
            folderPatternsSkip = "[\".*/skip-me(/.*)?$\"]"
            folderPatternsLocate = "[\".*\"]"
            filePatternsLocate = "[\".*\"]"
        })
    }
}
