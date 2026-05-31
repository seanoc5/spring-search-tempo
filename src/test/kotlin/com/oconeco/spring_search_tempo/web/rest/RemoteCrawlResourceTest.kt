package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.CrawlMode
import com.oconeco.spring_search_tempo.base.domain.CrawlTemperature
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.domain.HostCrawlSessionType
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.repos.HostCrawlSessionRepository
import com.oconeco.spring_search_tempo.base.service.DiscoveryService
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
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
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

    @Autowired
    private lateinit var fileRepository: FSFileRepository

    @Autowired
    private lateinit var folderRepository: FSFolderRepository

    @Autowired
    private lateinit var hostCrawlSessionRepository: HostCrawlSessionRepository

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
        val tree = discoveryService.getInitialFolderTree(sessionId, 2)
        val foldersByPath = tree.folders.associateBy { it.path }
        val homeChildrenByPath = discoveryService.getChildFolders(sessionId, "/home").associateBy { it.path }

        assertThat(foldersByPath["/"]?.parentPath).isNull()
        assertThat(foldersByPath["/home"]?.parentPath).isEqualTo("/")
        assertThat(foldersByPath["/var"]?.parentPath).isEqualTo("/")
        assertThat(homeChildrenByPath["/home/sean"]?.parentPath).isEqualTo("/home")
    }

    @Test
    fun `remote crawler fixture should classify queue and ingest all analysis levels`() {
        val requestHost = "FIXTURE-HOST"
        val normalizedHost = normalizeHost(requestHost)
        val crawlConfigId = createRemoteStatusFixtureCrawlConfig(normalizedHost)
        val fixtures = remoteStatusFixtures()

        val classifyResponse = RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "folders" to fixtures.map { mapOf("path" to it.folderPath) },
                        "files" to fixtures.map {
                            mapOf(
                                "path" to it.filePath,
                                "parentFolderStatus" to AnalysisStatus.LOCATE.name
                            )
                        }
                    )
                )
            .`when`()
                .post("/api/remote-crawl/classify")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("folders.size()", equalTo(fixtures.size))
                .body("files.size()", equalTo(fixtures.size))
                .extract()

        val folderStatuses = classifyResponse.jsonPath().getList("folders", Map::class.java)
            .associate { it["path"].toString() to AnalysisStatus.valueOf(it["analysisStatus"].toString()) }
        val fileStatuses = classifyResponse.jsonPath().getList("files", Map::class.java)
            .associate { it["path"].toString() to AnalysisStatus.valueOf(it["analysisStatus"].toString()) }
        val fileInstructions = classifyResponse.jsonPath().getList("files", Map::class.java)
            .associateBy({ it["path"].toString() }, { it["instructions"] as Map<*, *> })

        fixtures.forEach { fixture ->
            assertThat(folderStatuses[fixture.folderPath]).isEqualTo(fixture.expectedStatus)
            assertThat(fileStatuses[fixture.filePath]).isEqualTo(fixture.expectedStatus)
            val instructions = fileInstructions[fixture.filePath]
            assertThat(instructions?.get("persistMetadata")).isEqualTo(true)
            assertThat(instructions?.get("extractText")).isEqualTo(fixture.expectedStatus in setOf(
                AnalysisStatus.INDEX, AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC
            ))
            assertThat(instructions?.get("runNlp")).isEqualTo(fixture.expectedStatus in setOf(
                AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC
            ))
            assertThat(instructions?.get("runEmbedding")).isEqualTo(fixture.expectedStatus == AnalysisStatus.SEMANTIC)
        }

        val startResponse = RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "expectedTotal" to fixtures.size
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
                        "folders" to fixtures.map { mapOf("path" to it.folderPath) }
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/tasks/enqueue-folders")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("received", equalTo(fixtures.size))
                .body("queued", equalTo(fixtures.count { it.expectedStatus != AnalysisStatus.SKIP }))
                .body("skipped", equalTo(fixtures.count { it.expectedStatus == AnalysisStatus.SKIP }))

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
                .body("tasks.size()", equalTo(fixtures.count { it.expectedStatus != AnalysisStatus.SKIP }))
                .extract()

        val queuedStatuses = claimResponse.jsonPath().getList("tasks.analysisStatus", String::class.java)
        assertThat(queuedStatuses).containsExactlyInAnyOrder("LOCATE", "INDEX", "ANALYZE", "SEMANTIC")

        val ingestResponse = RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    mapOf(
                        "host" to requestHost,
                        "crawlConfigId" to crawlConfigId,
                        "sessionId" to sessionId,
                        "folders" to fixtures.map { fixture ->
                            mapOf(
                                "path" to fixture.folderPath,
                                "analysisStatus" to fixture.expectedStatus.name,
                                "fsLastModified" to fixture.modifiedAt.toString()
                            )
                        },
                        "files" to fixtures.map { fixture ->
                            mapOf(
                                "path" to fixture.filePath,
                                "analysisStatus" to fixture.expectedStatus.name,
                                "size" to fixture.bodyText.length.toLong(),
                                "fsLastModified" to fixture.modifiedAt.toString(),
                                "bodyText" to fixture.persistedBodyText,
                                "contentType" to "text/plain"
                            )
                        }
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/ingest")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("foldersReceived", equalTo(fixtures.size))
                .body("filesReceived", equalTo(fixtures.size))
                .body("foldersSkipped", equalTo(1))
                .body("filesSkipped", equalTo(1))
                .extract()

        assertThat(ingestResponse.jsonPath().getInt("filesPersisted")).isEqualTo(fixtures.size)

        fixtures.forEach { fixture ->
            val persistedFolder = folderRepository.findByUri(remoteUri(normalizedHost, fixture.folderPath))
            val persistedFile = fileRepository.findByUri(remoteUri(normalizedHost, fixture.filePath))

            assertThat(persistedFolder).isNotNull
            assertThat(persistedFolder!!.analysisStatus).isEqualTo(fixture.expectedStatus)
            assertThat(persistedFile).isNotNull
            assertThat(persistedFile!!.analysisStatus).isEqualTo(fixture.expectedStatus)
            assertThat(persistedFile.fsFolder?.uri).isEqualTo(remoteUri(normalizedHost, fixture.folderPath))
            assertThat(persistedFile.bodyText).isEqualTo(fixture.persistedBodyText)
        }
    }

    @Test
    fun `remote ingest should apply incremental updates and adjust smart crawl activity`() {
        val requestHost = "SMART-UPDATES"
        val normalizedHost = normalizeHost(requestHost)
        val crawlConfigId = createRemoteStatusFixtureCrawlConfig(normalizedHost, smartCrawlEnabled = true)
        val semanticFixture = remoteStatusFixtures().single { it.expectedStatus == AnalysisStatus.SEMANTIC }

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
        val session = hostCrawlSessionRepository.findByJobRunId(sessionId)
        assertThat(session?.sessionType).isEqualTo(HostCrawlSessionType.SMART)

        val firstModifiedAt = OffsetDateTime.now().minusHours(2).truncatedTo(ChronoUnit.SECONDS)
        val secondModifiedAt = firstModifiedAt.plusHours(1)

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    incrementalIngestBody(
                        host = requestHost,
                        crawlConfigId = crawlConfigId,
                        sessionId = sessionId,
                        fixture = semanticFixture,
                        modifiedAt = firstModifiedAt,
                        size = semanticFixture.bodyText.length.toLong()
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/ingest")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("filesNew", equalTo(1))
                .body("filesUpdated", equalTo(0))

        val folderUri = remoteUri(normalizedHost, semanticFixture.folderPath)
        val fileUri = remoteUri(normalizedHost, semanticFixture.filePath)
        val folderAfterFirst = folderRepository.findByUri(folderUri)!!
        assertThat(folderAfterFirst.changeScore).isEqualTo(10)
        assertThat(folderAfterFirst.crawlTemperature).isEqualTo(CrawlTemperature.HOT)
        assertThat(folderAfterFirst.childModifiedAt).isEqualTo(firstModifiedAt)
        assertThat(fileRepository.findByUri(fileUri)?.fsLastModified).isEqualTo(firstModifiedAt)

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    incrementalIngestBody(
                        host = requestHost,
                        crawlConfigId = crawlConfigId,
                        sessionId = sessionId,
                        fixture = semanticFixture,
                        modifiedAt = firstModifiedAt,
                        size = semanticFixture.bodyText.length.toLong()
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/ingest")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("filesNew", equalTo(0))
                .body("filesUpdated", equalTo(1))

        val folderAfterSecond = folderRepository.findByUri(folderUri)!!
        assertThat(folderAfterSecond.changeScore).isEqualTo(9)
        assertThat(folderAfterSecond.childModifiedAt).isEqualTo(firstModifiedAt)

        RestAssured
            .given()
                .contentType(ContentType.JSON)
                .body(
                    incrementalIngestBody(
                        host = requestHost,
                        crawlConfigId = crawlConfigId,
                        sessionId = sessionId,
                        fixture = semanticFixture,
                        modifiedAt = secondModifiedAt,
                        size = semanticFixture.bodyText.length.toLong() + 10
                    )
                )
            .`when`()
                .post("/api/remote-crawl/session/ingest")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("filesNew", equalTo(0))
                .body("filesUpdated", equalTo(1))

        val folderAfterThird = folderRepository.findByUri(folderUri)!!
        val fileAfterThird = fileRepository.findByUri(fileUri)!!
        assertThat(folderAfterThird.changeScore).isEqualTo(19)
        assertThat(folderAfterThird.childModifiedAt).isEqualTo(secondModifiedAt)
        assertThat(folderAfterThird.lastCrawledAt).isNotNull
        assertThat(fileAfterThird.fsLastModified).isEqualTo(secondModifiedAt)
        assertThat(fileAfterThird.size).isEqualTo(semanticFixture.bodyText.length.toLong() + 10)
    }

    @Test
    fun `smart bootstrap should prioritize hot warm then cold folders and exclude skipped`() {
        val requestHost = "SMART-LIST"
        val normalizedHost = normalizeHost(requestHost)
        val crawlConfigId = createRemoteStatusFixtureCrawlConfig(normalizedHost, smartCrawlEnabled = true)
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)

        folderRepository.saveAll(
            listOf(
                scheduledFolder(crawlConfigId, normalizedHost, "/projects/hot-alpha", CrawlTemperature.HOT, 90, now.minusHours(8)),
                scheduledFolder(crawlConfigId, normalizedHost, "/projects/hot-beta", CrawlTemperature.HOT, 55, now.minusHours(6)),
                scheduledFolder(crawlConfigId, normalizedHost, "/projects/warm-gamma", CrawlTemperature.WARM, 35, now.minusDays(2)),
                scheduledFolder(crawlConfigId, normalizedHost, "/projects/cold-delta", CrawlTemperature.COLD, 5, now.minusDays(10)),
                scheduledFolder(crawlConfigId, normalizedHost, "/projects/hot-skipped", CrawlTemperature.HOT, 99, now.minusHours(9), AnalysisStatus.SKIP)
            )
        )

        RestAssured
            .given()
                .queryParam("host", requestHost)
                .queryParam("crawlConfigId", crawlConfigId)
            .`when`()
                .get("/api/remote-crawl/smart-bootstrap")
            .then()
                .statusCode(HttpStatus.OK.value())
                .body("smartCrawlEnabled", equalTo(true))
                .body("standardBootstrap.assignments.size()", equalTo(1))
                .body("temperatureSummary.hotCount", equalTo(2))
                .body("temperatureSummary.warmCount", equalTo(1))
                .body("temperatureSummary.coldCount", equalTo(1))
                .body("temperatureSummary.totalDue", equalTo(4))
                .body("prioritizedFolders.size()", equalTo(4))
                .body("prioritizedFolders[0].path", equalTo("/projects/hot-alpha"))
                .body("prioritizedFolders[1].path", equalTo("/projects/hot-beta"))
                .body("prioritizedFolders[2].path", equalTo("/projects/warm-gamma"))
                .body("prioritizedFolders[3].path", equalTo("/projects/cold-delta"))
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

    private fun createRemoteStatusFixtureCrawlConfig(
        sourceHost: String,
        smartCrawlEnabled: Boolean = false
    ): Long {
        val suffix = System.currentTimeMillis()
        return crawlConfigService.create(CrawlConfigDTO().apply {
            name = "REMOTE_STATUS_IT_$suffix"
            label = "Remote Status IT $suffix"
            description = "Integration test crawl config for remote crawler status coverage"
            this.sourceHost = sourceHost
            startPaths = listOf("/fixture-status")
            maxDepth = 20
            followLinks = false
            parallel = false
            version = 0L
            this.smartCrawlEnabled = smartCrawlEnabled
            hotThresholdDays = 7
            warmThresholdDays = 30
            folderPatternsSkip = "[\".*/skip(/.*)?$\"]"
            folderPatternsLocate = "[\".*/locate(/.*)?$\"]"
            folderPatternsIndex = "[\".*/index(/.*)?$\"]"
            folderPatternsAnalyze = "[\".*/analyze(/.*)?$\"]"
            folderPatternsSemantic = "[\".*/semantic(/.*)?$\"]"
            filePatternsSkip = "[\".*/skip/.*$\"]"
            filePatternsLocate = "[\".*/locate/.*$\"]"
            filePatternsIndex = "[\".*/index/.*$\"]"
            filePatternsAnalyze = "[\".*/analyze/.*$\"]"
            filePatternsSemantic = "[\".*/semantic/.*$\"]"
        })
    }

    private fun remoteStatusFixtures(): List<RemoteFixtureFile> {
        val baseTime = OffsetDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS)
        return listOf(
            RemoteFixtureFile(
                expectedStatus = AnalysisStatus.SKIP,
                folderPath = "/fixture-status/skip",
                filePath = "/fixture-status/skip/ignore.tmp",
                bodyText = readResource("/fixtures/remote-crawler/status-fixtures/skip/ignore.tmp"),
                modifiedAt = baseTime.minusMinutes(5)
            ),
            RemoteFixtureFile(
                expectedStatus = AnalysisStatus.LOCATE,
                folderPath = "/fixture-status/locate/media",
                filePath = "/fixture-status/locate/media/photo.jpg",
                bodyText = readResource("/fixtures/remote-crawler/status-fixtures/locate/media/photo.jpg"),
                modifiedAt = baseTime.minusMinutes(4)
            ),
            RemoteFixtureFile(
                expectedStatus = AnalysisStatus.INDEX,
                folderPath = "/fixture-status/index/docs",
                filePath = "/fixture-status/index/docs/manual.txt",
                bodyText = readResource("/fixtures/remote-crawler/status-fixtures/index/docs/manual.txt"),
                modifiedAt = baseTime.minusMinutes(3)
            ),
            RemoteFixtureFile(
                expectedStatus = AnalysisStatus.ANALYZE,
                folderPath = "/fixture-status/analyze/code",
                filePath = "/fixture-status/analyze/code/architecture.md",
                bodyText = readResource("/fixtures/remote-crawler/status-fixtures/analyze/code/architecture.md"),
                modifiedAt = baseTime.minusMinutes(2)
            ),
            RemoteFixtureFile(
                expectedStatus = AnalysisStatus.SEMANTIC,
                folderPath = "/fixture-status/semantic/knowledge",
                filePath = "/fixture-status/semantic/knowledge/embedding-notes.txt",
                bodyText = readResource("/fixtures/remote-crawler/status-fixtures/semantic/knowledge/embedding-notes.txt"),
                modifiedAt = baseTime.minusMinutes(1)
            )
        )
    }

    private fun incrementalIngestBody(
        host: String,
        crawlConfigId: Long,
        sessionId: Long,
        fixture: RemoteFixtureFile,
        modifiedAt: OffsetDateTime,
        size: Long
    ): Map<String, Any?> {
        return mapOf(
            "host" to host,
            "crawlConfigId" to crawlConfigId,
            "sessionId" to sessionId,
            "folders" to listOf(
                mapOf(
                    "path" to fixture.folderPath,
                    "analysisStatus" to fixture.expectedStatus.name,
                    "fsLastModified" to modifiedAt.toString()
                )
            ),
            "files" to listOf(
                mapOf(
                    "path" to fixture.filePath,
                    "analysisStatus" to fixture.expectedStatus.name,
                    "fsLastModified" to modifiedAt.toString(),
                    "size" to size,
                    "bodyText" to fixture.persistedBodyText,
                    "contentType" to "text/plain"
                )
            )
        )
    }

    private fun scheduledFolder(
        crawlConfigId: Long,
        sourceHost: String,
        path: String,
        temperature: CrawlTemperature,
        changeScore: Int,
        lastCrawledAt: OffsetDateTime,
        analysisStatus: AnalysisStatus = AnalysisStatus.ANALYZE
    ): FSFolder {
        return FSFolder().apply {
            uri = remoteUri(sourceHost, path)
            version = 0L
            type = "FOLDER"
            status = Status.CURRENT
            this.analysisStatus = analysisStatus
            label = path.substringAfterLast('/')
            this.crawlConfigId = crawlConfigId
            this.sourceHost = sourceHost
            this.crawlTemperature = temperature
            this.changeScore = changeScore
            this.lastCrawledAt = lastCrawledAt
            this.childModifiedAt = lastCrawledAt.minusHours(1)
        }
    }

    private fun remoteUri(host: String, path: String): String = "remote://${normalizeHost(host)}$path"

    private fun normalizeHost(host: String): String =
        host.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-")

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(bytes.size)
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private data class RemoteFixtureFile(
        val expectedStatus: AnalysisStatus,
        val folderPath: String,
        val filePath: String,
        val bodyText: String,
        val modifiedAt: OffsetDateTime
    ) {
        val persistedBodyText: String?
            get() = when (expectedStatus) {
                AnalysisStatus.SKIP, AnalysisStatus.LOCATE -> null
                else -> bodyText
            }
    }
}
