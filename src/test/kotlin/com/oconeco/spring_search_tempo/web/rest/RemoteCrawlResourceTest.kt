package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus

@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RemoteCrawlResourceTest : BaseIT() {

    @Autowired
    private lateinit var crawlConfigService: DatabaseCrawlConfigService

    @Test
    fun `remote queue lifecycle should support start enqueue claim ingest ack and complete`() {
        val requestHost = "WIN11-DEVBOX"
        val crawlConfigId = createRemoteTestCrawlConfig()

        val startResponse = RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "expectedTotal" to 10
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/start")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("sessionId", notNullValue())
                .body("crawlConfigId", equalTo(crawlConfigId.toInt()))
                .extract()

        val sessionId = startResponse.jsonPath().getLong("sessionId")

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "sessionId" to sessionId,
                        "folders" to listOf(
                            mapOf("path" to "/data/keep-one"),
                            mapOf("path" to "/data/skip-me")
                        )
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/tasks/enqueue-folders")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("received", equalTo(2))
                .body("queued", equalTo(1))
                .body("skipped", equalTo(1))

        val claimResponse = RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "sessionId" to sessionId,
                        "maxTasks" to 10
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/tasks/next")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("claimToken", notNullValue())
                .body("tasks.size()", equalTo(1))
                .body("tasks[0].folderPath", equalTo("/data/keep-one"))
                .extract()

        val claimToken = claimResponse.jsonPath().getString("claimToken")
        val taskId = claimResponse.jsonPath().getLong("tasks[0].taskId")

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "sessionId" to sessionId,
                        "folders" to emptyList<Map<String, Any>>(),
                        "files" to listOf(
                            mapOf(
                                "path" to "/data/keep-one/readme.txt",
                                "analysisStatus" to "ANALYZE",
                                "bodyText" to "Remote integration test body text",
                                "contentType" to "text/plain"
                            )
                        )
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/ingest")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("foldersReceived", equalTo(0))
                .body("filesReceived", equalTo(1))
                .body("filesPersisted", equalTo(1))

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "sessionId" to sessionId,
                        "claimToken" to claimToken,
                        "results" to listOf(
                            mapOf(
                                "taskId" to taskId,
                                "outcome" to "COMPLETED"
                            )
                        ),
                        "processedIncrement" to 1
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/tasks/ack")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("updated", equalTo(1))
                .body("completed", equalTo(1))
                .body("queueStatus.COMPLETED", equalTo(1))

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "sessionId" to sessionId
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/tasks/status")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("queueStatus.COMPLETED", equalTo(1))
                .body("queueStatus.PENDING", equalTo(0))

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "sessionId" to sessionId,
                        "runStatus" to "COMPLETED",
                        "finalStep" to "Integration test complete"
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/complete")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("sessionId", equalTo(sessionId.toInt()))
                .body("runStatus", equalTo("COMPLETED"))
    }

    private fun createRemoteTestCrawlConfig(): Long {
        val suffix = System.currentTimeMillis()
        return crawlConfigService.create(CrawlConfigDTO().apply {
            name = "REMOTE_IT_$suffix"
            label = "Remote IT $suffix"
            description = "Integration test crawl config"
            enabled = true
            startPaths = listOf("/data")
            maxDepth = 20
            followLinks = false
            parallel = false
            version = 0L
            folderPatternsSkip = "[\".*/skip-me$\"]"
            folderPatternsLocate = "[\".*\"]"
            filePatternsLocate = "[\".*\"]"
        })
    }
}
