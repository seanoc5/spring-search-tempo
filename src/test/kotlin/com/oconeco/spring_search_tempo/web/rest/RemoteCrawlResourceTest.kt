package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.CrawlMode
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.web.service.DiscoveryService
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RemoteCrawlResourceTest : BaseIT() {

    @Autowired
    private lateinit var crawlConfigService: DatabaseCrawlConfigService

    @Autowired
    private lateinit var discoveryService: DiscoveryService

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

    @Test
    fun `discovery observation api should ingest reapply and support manual override`() {
        val requestHost = "DISCOVERY-HOST"
        val normalizedHost = "discovery-host"
        val crawlConfigId = createRemoteDiscoveryTestCrawlConfig(normalizedHost)

        val startResponse = RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/start")
            .then()
                .statusCode(HttpStatus.OK.value())
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
                        "folders" to emptyList<Map<String, Any>>(),
                        "files" to emptyList<Map<String, Any>>(),
                        "discoveryFolders" to listOf(
                            mapOf("path" to "/data/skip-me", "depth" to 1, "inSkipBranch" to true),
                            mapOf("path" to "/data/skip-me/n1", "depth" to 2, "inSkipBranch" to true),
                            mapOf("path" to "/data/keep-me", "depth" to 1, "inSkipBranch" to true)
                        ),
                        "discoveryFileSamples" to listOf(
                            mapOf("folderPath" to "/data/skip-me", "sampleSlot" to 1, "fileName" to "a.txt", "fileSize" to 10),
                            mapOf("folderPath" to "/data/skip-me", "sampleSlot" to 2, "fileName" to "b.txt", "fileSize" to 20),
                            mapOf("folderPath" to "/data/keep-me", "sampleSlot" to 1, "fileName" to "k.txt", "fileSize" to 30)
                        )
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/ingest")
            .then()
                .statusCode(HttpStatus.OK.value())

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "jobRunId" to sessionId
                    )
                )
            .`when`()
                .post("/api/remote-crawl/discovery/reapply-skip")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("status", equalTo("OK"))
                .body("total", equalTo(3))
                .body("changed", equalTo(2))

        RestAssured
            .given()
                .queryParam("crawlConfigId", crawlConfigId)
                .queryParam("host", requestHost)
                .queryParam("includeSamples", true)
                .queryParam("page", 0)
                .queryParam("limit", 50)
            .`when`()
                .get("/api/remote-crawl/discovery/observations")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("count", equalTo(3))
                .body("totalCount", equalTo(3))
                .body("page", equalTo(0))
                .body("totalPages", equalTo(1))
                .body("observations.find { it.path == '/data/skip-me' }.skipByCurrentRules", equalTo(true))
                .body("observations.find { it.path == '/data/skip-me/n1' }.skipByCurrentRules", equalTo(true))
                .body("observations.find { it.path == '/data/keep-me' }.skipByCurrentRules", equalTo(false))
                .body("observations.find { it.path == '/data/keep-me' }.fileSamples.size()", equalTo(1))

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "path" to "/data/keep-me",
                        "manualOverride" to "FORCE_SKIP"
                    )
                )
            .`when`()
                .post("/api/remote-crawl/discovery/override")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("status", equalTo("OK"))
                .body("observation.path", equalTo("/data/keep-me"))
                .body("observation.manualOverride", equalTo("FORCE_SKIP"))
                .body("observation.skipByCurrentRules", equalTo(true))

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "path" to "/data/keep-me"
                    )
                )
            .`when`()
                .post("/api/remote-crawl/discovery/override")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("status", equalTo("OK"))
                .body("observation.skipByCurrentRules", equalTo(false))

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "sessionId" to sessionId,
                        "runStatus" to "COMPLETED"
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/complete")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("runStatus", equalTo("COMPLETED"))
    }

    @Test
    fun `discovery upload should accept gzipped json`() {
        val payload = """
            {
              "host": "gzip-host",
              "folders": [
                {
                  "path": "/data",
                  "name": "data",
                  "depth": 0,
                  "folderCount": 1,
                  "fileCount": 0,
                  "totalSize": 0,
                  "isHidden": false,
                  "suggestedStatus": "LOCATE"
                }
              ],
              "rootPaths": ["/data"],
              "osType": "LINUX",
              "discoveryDurationMs": 1234,
              "createNewSession": true
            }
        """.trimIndent().toByteArray()

        RestAssured
            .given()
                .header("Content-Type", ContentType.JSON.toString())
                .header("Accept", ContentType.JSON.toString())
                .header("Content-Encoding", "gzip")
                .body(gzip(payload))
            .`when`()
                .post("/api/remote-crawl/discovery/upload")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("host", equalTo("gzip-host"))
                .body("foldersReceived", equalTo(1))
                .body("sessionId", notNullValue())
    }

    @Test
    fun `discovery classification should keep unix root children attached to slash root`() {
        val uploadResponse = RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to "linux-root-host",
                        "folders" to listOf(
                            mapOf("path" to "/", "name" to "/", "depth" to 0),
                            mapOf("path" to "/home", "name" to "home", "depth" to 1),
                            mapOf("path" to "/var", "name" to "var", "depth" to 1),
                            mapOf("path" to "/home/sean", "name" to "sean", "depth" to 2)
                        ),
                        "rootPaths" to listOf("/"),
                        "osType" to "LINUX",
                        "discoveryDurationMs" to 42,
                        "createNewSession" to true
                    )
                )
            .`when`()
                .post("/api/remote-crawl/discovery/upload")
            .then()
                .statusCode(HttpStatus.OK.value())
                .extract()

        val sessionId = uploadResponse.jsonPath().getLong("sessionId")
        val session = discoveryService.getSessionForClassification(sessionId, 2)
        val foldersByPath = session.folders.associateBy { it.path }

        assertThat(foldersByPath["/"]?.parentPath).isNull()
        assertThat(foldersByPath["/home"]?.parentPath).isEqualTo("/")
        assertThat(foldersByPath["/var"]?.parentPath).isEqualTo("/")
        assertThat(foldersByPath["/home/sean"]?.parentPath).isEqualTo("/home")
    }

    private fun createRemoteTestCrawlConfig(): Long {
        val suffix = System.currentTimeMillis()
        return crawlConfigService.create(CrawlConfigDTO().apply {
            name = "REMOTE_IT_$suffix"
            label = "Remote IT $suffix"
            description = "Integration test crawl config"
            sourceHost = "win11-devbox"
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

    private fun createRemoteDiscoveryTestCrawlConfig(sourceHost: String): Long {
        val suffix = System.currentTimeMillis()
        return crawlConfigService.create(CrawlConfigDTO().apply {
            name = "REMOTE_DISCOVERY_IT_$suffix"
            label = "Remote Discovery IT $suffix"
            description = "Integration test crawl config for discovery observation APIs"
            this.sourceHost = sourceHost
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

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(bytes.size)
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}
