package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.oconeco.spring_search_tempo.web.service.DiscoveryService
import com.oconeco.spring_search_tempo.web.service.DiscoveryUploadRequest
import com.oconeco.spring_search_tempo.web.service.DryRunService
import com.oconeco.spring_search_tempo.web.service.RemoteClassifyRequest
import com.oconeco.spring_search_tempo.web.service.RemoteCrawlPlannerService
import com.oconeco.spring_search_tempo.web.service.RemoteCrawlSessionService
import com.oconeco.spring_search_tempo.web.service.RemoteCrawlTaskService
import com.oconeco.spring_search_tempo.web.service.RemoteIngestRequest
import com.oconeco.spring_search_tempo.web.service.RemoteEnqueueFoldersRequest
import com.oconeco.spring_search_tempo.web.service.RemoteSessionCompleteRequest
import com.oconeco.spring_search_tempo.web.service.RemoteSessionHeartbeatRequest
import com.oconeco.spring_search_tempo.web.service.RemoteSessionStartRequest
import com.oconeco.spring_search_tempo.web.service.RemoteTaskAckRequest
import com.oconeco.spring_search_tempo.web.service.RemoteTaskClaimRequest
import com.oconeco.spring_search_tempo.web.service.RemoteTaskStatusRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/api/remote-crawl")
class RemoteCrawlResource(
    private val remoteCrawlPlannerService: RemoteCrawlPlannerService,
    private val remoteCrawlSessionService: RemoteCrawlSessionService,
    private val remoteCrawlTaskService: RemoteCrawlTaskService,
    private val discoveryService: DiscoveryService,
    private val dryRunService: DryRunService
) {

    companion object {
        private val log = LoggerFactory.getLogger(RemoteCrawlResource::class.java)
    }

    /**
     * Unauthenticated connectivity probe for remote crawler clients.
     */
    @GetMapping("/health")
    fun health(): ResponseEntity<Any> =
        ResponseEntity.ok(
            mapOf(
                "status" to "UP",
                "service" to "remote-crawl"
            )
        )

    /**
     * Authenticated probe for remote crawler clients.
     */
    @GetMapping("/auth-check")
    fun authCheck(principal: Principal): ResponseEntity<Any> =
        ResponseEntity.ok(
            mapOf(
                "status" to "OK",
                "authenticated" to true,
                "user" to principal.name
            )
        )

    @GetMapping("/bootstrap")
    fun bootstrap(
        @RequestParam(name = "host") host: String
    ): ResponseEntity<Any> {
        log.info("Remote bootstrap request for host {}", host)
        return try {
            val response = remoteCrawlPlannerService.buildBootstrap(host)
            ResponseEntity.ok(response)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Remote bootstrap failed for host {}", host, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to generate remote crawl bootstrap: ${e.message}"
                )
            )
        }
    }

    /**
     * Smart bootstrap endpoint with temperature-based folder prioritization.
     *
     * Returns standard bootstrap config plus a prioritized list of folders
     * based on their crawl temperature (HOT, WARM, COLD).
     *
     * If the config has smartCrawlEnabled=false, returns standard bootstrap only.
     *
     * @param host Remote host name
     * @param crawlConfigId ID of the crawl config to use for prioritization
     */
    @GetMapping("/smart-bootstrap")
    fun smartBootstrap(
        @RequestParam(name = "host") host: String,
        @RequestParam(name = "crawlConfigId") crawlConfigId: Long
    ): ResponseEntity<Any> {
        log.info("Smart bootstrap request for host {}, config {}", host, crawlConfigId)
        return try {
            val response = remoteCrawlPlannerService.buildSmartBootstrap(host, crawlConfigId)
            ResponseEntity.ok(response)
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Crawl config not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Smart bootstrap failed for host {}, config {}", host, crawlConfigId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to generate smart bootstrap: ${e.message}"
                )
            )
        }
    }

    @PostMapping("/classify")
    fun classify(@RequestBody request: RemoteClassifyRequest): ResponseEntity<Any> {
        log.info("Remote classify request for config {}", request.crawlConfigId)
        return try {
            val response = remoteCrawlPlannerService.classify(request)
            ResponseEntity.ok(response)
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Crawl config not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Remote classify failed for config {}", request.crawlConfigId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to classify remote paths: ${e.message}"
                )
            )
        }
    }

    /**
     * Discovery-session picker endpoint for dry-run clients.
     * Returns ranked candidates (host match first, then recency).
     */
    @GetMapping("/discovery/sessions")
    fun discoverySessions(
        @RequestParam(name = "configId") configId: Long,
        @RequestParam(name = "host", required = false) host: String?,
        @RequestParam(name = "limit", required = false, defaultValue = "3") limit: Int
    ): ResponseEntity<Any> {
        return try {
            val sessions = dryRunService.listSessionCandidates(
                configId = configId,
                requestedHost = host,
                limit = limit.coerceIn(1, 50)
            )
            ResponseEntity.ok(
                mapOf(
                    "configId" to configId,
                    "requestedHost" to host,
                    "count" to sessions.size,
                    "sessions" to sessions
                )
            )
        } catch (e: Exception) {
            log.error("Discovery session options failed for config {}", configId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to list discovery sessions: ${e.message}"
                )
            )
        }
    }

    /**
     * Dry run endpoint: Preview how folders would be classified during a crawl.
     *
     * Uses discovery session data (from onboarding) to show what would happen
     * without actually executing the crawl.
     *
     * @param configId Required crawl config ID
     * @param detailed If true, return all folders. If false, only explicit pattern matches.
     * @param sessionId Optional discovery session ID. If not provided, uses most recent.
     * @param status Optional status filter (SKIP, LOCATE, INDEX, ANALYZE, SEMANTIC)
     * @param pathPrefix Optional path prefix to filter results
     * @param limit Maximum folders to return (default 10000)
     */
    @GetMapping("/dry-run")
    fun dryRun(
        @RequestParam(name = "configId") configId: Long,
        @RequestParam(name = "detailed", required = false, defaultValue = "false") detailed: Boolean,
        @RequestParam(name = "sessionId", required = false) sessionId: Long?,
        @RequestParam(name = "status", required = false) status: String?,
        @RequestParam(name = "pathPrefix", required = false) pathPrefix: String?,
        @RequestParam(name = "limit", required = false, defaultValue = "10000") limit: Int
    ): ResponseEntity<Any> {
        log.info("Dry run request for config {}, detailed={}, sessionId={}", configId, detailed, sessionId)
        return try {
            val statusFilter = status?.uppercase()?.let {
                try {
                    AnalysisStatus.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid status: $status. Valid values: SKIP, LOCATE, INDEX, ANALYZE, SEMANTIC")
                }
            }
            val response = dryRunService.generateDryRun(
                configId = configId,
                detailed = detailed,
                sessionId = sessionId,
                statusFilter = statusFilter,
                pathPrefix = pathPrefix,
                limit = limit.coerceIn(1, 100000)
            )
            ResponseEntity.ok(response)
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Dry run failed for config {}", configId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to generate dry run: ${e.message}"
                )
            )
        }
    }

    @PostMapping("/session/start")
    fun startSession(@RequestBody request: RemoteSessionStartRequest): ResponseEntity<Any> {
        return try {
            ResponseEntity.ok(remoteCrawlSessionService.start(request))
        } catch (e: NotFoundException) {
            log.error("Remote session start failed for config {}", request.crawlConfigId, e)
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Crawl config not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Remote session start failed for config {}", request.crawlConfigId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to start remote session: ${e.message}"
                )
            )
        }
    }

    @PostMapping("/session/heartbeat")
    fun heartbeat(@RequestBody request: RemoteSessionHeartbeatRequest): ResponseEntity<Any> {
        log.info("Remote session heartbeat for session {}", request.sessionId)
        return try {
            ResponseEntity.ok(remoteCrawlSessionService.heartbeat(request))
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Remote session not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Remote session heartbeat failed for session {}", request.sessionId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to process heartbeat: ${e.message}"
                )
            )
        }
    }

    @PostMapping("/session/ingest")
    fun ingest(@RequestBody request: RemoteIngestRequest): ResponseEntity<Any> {
        if (request.folders != null) {
            val firstFolder = request.folders?.firstOrNull()
            val fileCount = request.files?.size ?: 0
            log.info(
                "Remote ingest: session {} -- Host: {} -- folder(s):{} -- fileCount:{}",
                request.sessionId,
                request.host,
                firstFolder,
                fileCount
            )
        } else {
            log.warn("Remote ingest: session {} -- Host: {} -- folder(s):{} (null?!)", request.sessionId, request.host, request.folders)
        }
        return try {
            ResponseEntity.ok(remoteCrawlSessionService.ingest(request))
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Remote session not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Remote ingest failed for session {}", request.sessionId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to ingest remote crawl batch: ${e.message}"
                )
            )
        }
    }

    @PostMapping("/session/complete")
    fun complete(@RequestBody request: RemoteSessionCompleteRequest): ResponseEntity<Any> {
        log.info("Remote session complete request for session {}", request.sessionId)
        return try {
            ResponseEntity.ok(remoteCrawlSessionService.complete(request))
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Remote session not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Remote session complete failed for session {}", request.sessionId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to complete remote session: ${e.message}"
                )
            )
        }
    }

    @PostMapping("/session/tasks/enqueue-folders")
    fun enqueueFolders(@RequestBody request: RemoteEnqueueFoldersRequest): ResponseEntity<Any> {
        log.info("Remote enqueue-folders request for session {}", request.sessionId)
        return try {
            ResponseEntity.ok(remoteCrawlTaskService.enqueueFolders(request))
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Remote session not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Remote enqueue-folders failed for session {}", request.sessionId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to enqueue remote folders: ${e.message}"
                )
            )
        }
    }

    @PostMapping("/session/tasks/next")
    fun nextTasks(@RequestBody request: RemoteTaskClaimRequest): ResponseEntity<Any> {
        log.info("Remote task claim request for session {}", request.sessionId)
        return try {
            ResponseEntity.ok(remoteCrawlTaskService.claimNext(request))
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Remote session not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Remote task claim failed for session {}", request.sessionId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to claim remote tasks: ${e.message}"
                )
            )
        }
    }

    @PostMapping("/session/tasks/ack")
    fun ackTasks(@RequestBody request: RemoteTaskAckRequest): ResponseEntity<Any> {
        log.info("Remote task ack request for session {}", request.sessionId)
        return try {
            ResponseEntity.ok(remoteCrawlTaskService.ack(request))
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Remote session not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Remote task ack failed for session {}", request.sessionId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to ack remote tasks: ${e.message}"
                )
            )
        }
    }

    @PostMapping("/session/tasks/status")
    fun taskStatus(@RequestBody request: RemoteTaskStatusRequest): ResponseEntity<Any> {
        log.info("Remote task status request for session {}", request.sessionId)
        return try {
            ResponseEntity.ok(remoteCrawlTaskService.status(request))
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Remote session not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Remote task status failed for session {}", request.sessionId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to get remote task status: ${e.message}"
                )
            )
        }
    }

    // ============ Discovery (Onboarding) Endpoints ============

    @PostMapping("/discovery/upload")
    fun uploadDiscovery(@RequestBody request: DiscoveryUploadRequest): ResponseEntity<Any> {
        log.info("Discovery upload from host {} with {} folders", request.host, request.folders.size)
        return try {
            ResponseEntity.ok(discoveryService.uploadDiscovery(request))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            log.error("Discovery upload failed for host {}", request.host, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to process discovery upload: ${e.message}"
                )
            )
        }
    }

    @GetMapping("/discovery/status")
    fun discoveryStatus(
        @RequestParam(name = "sessionId") sessionId: Long
    ): ResponseEntity<Any> {
        return try {
            ResponseEntity.ok(discoveryService.getStatus(sessionId))
        } catch (e: NotFoundException) {
            ResponseEntity.status(404).body(
                mapOf(
                    "status" to "FAILED",
                    "message" to (e.message ?: "Discovery session not found")
                )
            )
        } catch (e: Exception) {
            log.error("Discovery status failed for session {}", sessionId, e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to get discovery status: ${e.message}"
                )
            )
        }
    }

    @GetMapping("/discovery/pending")
    fun pendingDiscoveries(): ResponseEntity<Any> {
        return try {
            ResponseEntity.ok(discoveryService.getPendingSessions())
        } catch (e: Exception) {
            log.error("Failed to get pending discoveries", e)
            ResponseEntity.internalServerError().body(
                mapOf(
                    "status" to "FAILED",
                    "message" to "Failed to get pending discoveries: ${e.message}"
                )
            )
        }
    }
}
