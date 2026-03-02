package com.oconeco.spring_search_tempo.web.rest

import com.oconeco.spring_search_tempo.base.util.NotFoundException
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

@RestController
@RequestMapping("/api/remote-crawl")
class RemoteCrawlResource(
    private val remoteCrawlPlannerService: RemoteCrawlPlannerService,
    private val remoteCrawlSessionService: RemoteCrawlSessionService,
    private val remoteCrawlTaskService: RemoteCrawlTaskService
) {

    companion object {
        private val log = LoggerFactory.getLogger(RemoteCrawlResource::class.java)
    }

    @GetMapping("/bootstrap")
    fun bootstrap(
        @RequestParam(name = "host") host: String
    ): ResponseEntity<Any> {
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

    @PostMapping("/classify")
    fun classify(@RequestBody request: RemoteClassifyRequest): ResponseEntity<Any> {
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

    @PostMapping("/session/start")
    fun startSession(@RequestBody request: RemoteSessionStartRequest): ResponseEntity<Any> {
        return try {
            ResponseEntity.ok(remoteCrawlSessionService.start(request))
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
}
