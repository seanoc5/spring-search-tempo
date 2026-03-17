package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.CrawlMode
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.web.service.DiscoveredFolderUploadDTO
import com.oconeco.spring_search_tempo.web.service.DiscoveryService
import com.oconeco.spring_search_tempo.web.service.DiscoveryUploadRequest
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus

@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class DiscoveryIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var crawlConfigService: DatabaseCrawlConfigService

    @Autowired
    private lateinit var discoveryService: DiscoveryService

    @Test
    fun `classify page lazy loads deeper folders while keeping configured focus paths`() {
        val host = "lazy-discovery-host-${System.currentTimeMillis()}"
        createCrawlConfig(host)

        val sessionId = discoveryService.uploadDiscovery(
            DiscoveryUploadRequest(
                host = host,
                osType = "LINUX",
                rootPaths = listOf("/"),
                discoveryDurationMs = 1000,
                createNewSession = true,
                folders = listOf(
                    folder("/", "", 0, folderCount = 2),
                    folder("/home", "home", 1, folderCount = 1),
                    folder("/home/sean", "sean", 2, folderCount = 1),
                    folder("/home/sean/Documents", "Documents", 3, folderCount = 1),
                    folder("/home/sean/Documents/projectA", "projectA", 4),
                    folder("/opt", "opt", 1, folderCount = 1),
                    folder("/opt/work", "work", 2, folderCount = 1),
                    folder("/opt/work/demo", "demo", 3)
                )
            )
        ).sessionId

        RestAssured
            .given()
                .accept(ContentType.HTML)
                .queryParam("maxDepth", 4)
            .`when`()
                .get("/discovery/$sessionId/classify")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("Classify Folders"))
                .body(containsString("/home/sean/Documents"))
                .body(containsString("/opt/work"))
                .body(not(containsString("/home/sean/Documents/projectA")))
                .body(not(containsString("/opt/work/demo")))

        RestAssured
            .given()
                .accept(ContentType.HTML)
                .queryParam("parentPath", "/home/sean/Documents")
                .queryParam("depth", 4)
                .queryParam("maxDepth", 4)
                .queryParam("inheritedStatus", AnalysisStatus.INDEX.name)
            .`when`()
                .get("/discovery/$sessionId/children")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body(containsString("/home/sean/Documents/projectA"))
    }

    private fun createCrawlConfig(sourceHost: String): Long {
        val suffix = System.currentTimeMillis()
        return crawlConfigService.create(CrawlConfigDTO().apply {
            name = "DISCOVERY_LAZY_IT_$suffix"
            label = "Discovery Lazy IT $suffix"
            description = "Integration test crawl config for lazy discovery tree"
            this.sourceHost = sourceHost
            enabled = true
            startPaths = listOf("/home/sean/Documents", "/opt/work")
            maxDepth = 20
            followLinks = false
            parallel = false
            version = 0L
            crawlMode = CrawlMode.DISCOVERY
            folderPatternsLocate = "[\".*\"]"
            filePatternsLocate = "[\".*\"]"
        })
    }

    private fun folder(
        path: String,
        name: String,
        depth: Int,
        folderCount: Int = 0
    ): DiscoveredFolderUploadDTO = DiscoveredFolderUploadDTO(
        path = path,
        name = name,
        depth = depth,
        folderCount = folderCount
    )
}
