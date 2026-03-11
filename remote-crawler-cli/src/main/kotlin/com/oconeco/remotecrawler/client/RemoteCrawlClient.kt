package com.oconeco.remotecrawler.client

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.oconeco.remotecrawler.model.AnalysisStatus
import com.oconeco.remotecrawler.model.PatternPriority
import com.oconeco.remotecrawler.model.PatternSet
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * HTTP client for communicating with the Spring Search Tempo server.
 *
 * Handles:
 * - Bootstrap: Get assigned crawl configs for this host
 * - Session lifecycle: start, heartbeat, complete
 * - Ingest: Send folder/file metadata + content
 * - Task queue: enqueue, claim, ack tasks
 */
class RemoteCrawlClient(
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val timeoutSeconds: Long = 30
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val authHeader: String? = if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
    } else {
        null
    }

    private val apiBase: String = "$baseUrl/api/remote-crawl"

    /**
     * Get bootstrap configuration for this host.
     * Returns list of assigned crawl configs with patterns.
     */
    fun bootstrap(host: String): BootstrapResponse {
        val response = get("$apiBase/bootstrap?host=$host")
        return objectMapper.readValue(response)
    }

    /**
     * Start a remote crawl session.
     */
    fun startSession(request: SessionStartRequest): SessionStartResponse {
        val response = post("$apiBase/session/start", request)
        return objectMapper.readValue(response)
    }

    /**
     * Send heartbeat to keep session alive.
     */
    fun heartbeat(request: SessionHeartbeatRequest): HeartbeatResponse {
        val response = post("$apiBase/session/heartbeat", request)
        return objectMapper.readValue(response)
    }

    /**
     * Ingest batch of folders and files.
     */
    fun ingest(request: IngestRequest): IngestResponse {
        val response = post("$apiBase/session/ingest", request)
        return objectMapper.readValue(response)
    }

    /**
     * Complete the crawl session.
     */
    fun completeSession(request: SessionCompleteRequest): SessionCompleteResponse {
        val response = post("$apiBase/session/complete", request)
        return objectMapper.readValue(response)
    }

    /**
     * Enqueue discovered folders for processing.
     */
    fun enqueueFolders(request: EnqueueFoldersRequest): EnqueueFoldersResponse {
        val response = post("$apiBase/session/tasks/enqueue-folders", request)
        return objectMapper.readValue(response)
    }

    /**
     * Claim next batch of tasks.
     */
    fun claimTasks(request: TaskClaimRequest): TaskClaimResponse {
        val response = post("$apiBase/session/tasks/next", request)
        return objectMapper.readValue(response)
    }

    /**
     * Acknowledge completed/failed tasks.
     */
    fun ackTasks(request: TaskAckRequest): TaskAckResponse {
        val response = post("$apiBase/session/tasks/ack", request)
        return objectMapper.readValue(response)
    }

    /**
     * Get task queue status.
     */
    fun taskStatus(request: TaskStatusRequest): TaskStatusResponse {
        val response = post("$apiBase/session/tasks/status", request)
        return objectMapper.readValue(response)
    }

    // ============ Discovery (Onboarding) ============

    /**
     * Upload discovered folders for classification.
     * This is the first step in the onboarding flow.
     */
    fun uploadDiscovery(request: DiscoveryUploadRequest): DiscoveryUploadResponse {
        val response = post("$apiBase/discovery/upload", request)
        return objectMapper.readValue(response)
    }

    /**
     * Get classification status for a discovery session.
     */
    fun getDiscoveryStatus(host: String, sessionId: Long): DiscoveryStatusResponse {
        val response = get("$apiBase/discovery/status?host=$host&sessionId=$sessionId")
        return objectMapper.readValue(response)
    }

    /**
     * Get dry run preview for a crawl config.
     * Shows how folders would be classified during a crawl.
     */
    fun getDryRun(
        configId: Long,
        detailed: Boolean = false,
        sessionId: Long? = null,
        status: String? = null,
        pathPrefix: String? = null,
        limit: Int = 10000
    ): DryRunResponse {
        val params = mutableListOf("configId=$configId", "detailed=$detailed", "limit=$limit")
        sessionId?.let { params.add("sessionId=$it") }
        status?.let { params.add("status=$it") }
        pathPrefix?.let { params.add("pathPrefix=${java.net.URLEncoder.encode(it, "UTF-8")}") }
        val response = get("$apiBase/dry-run?${params.joinToString("&")}")
        return objectMapper.readValue(response)
    }

    /**
     * List discovery session candidates for a crawl config.
     * Ranked on the server by host match and recency.
     */
    fun listDiscoverySessions(
        configId: Long,
        requestedHost: String? = null,
        limit: Int = 3
    ): List<DiscoverySessionOption> {
        val params = mutableListOf("configId=$configId", "limit=$limit")
        requestedHost?.trim()?.takeIf { it.isNotBlank() }?.let {
            params.add("host=${java.net.URLEncoder.encode(it, "UTF-8")}")
        }
        val response = get("$apiBase/discovery/sessions?${params.joinToString("&")}")
        return objectMapper.readValue<DiscoverySessionOptionsResponse>(response).sessions
    }

    fun hasCredentials(): Boolean = authHeader != null

    /**
     * Test unauthenticated connectivity to the remote-crawl API.
     */
    fun testConnectivity(): ConnectivityCheckResult {
        val remoteHealthUrl = "$apiBase/health"
        log.debug("Testing unauthenticated connectivity to: {}", remoteHealthUrl)
        val primary = requestCheck(url = remoteHealthUrl, includeAuth = false)
        if (primary.ok) return primary

        // Backward-compatible fallback for older servers.
        val actuatorHealthUrl = "$baseUrl/actuator/health"
        log.debug(
            "Remote health check failed ({}). Trying fallback {}",
            primary.message,
            actuatorHealthUrl
        )
        val fallback = requestCheck(url = actuatorHealthUrl, includeAuth = false)
        return if (fallback.ok) {
            fallback.copy(message = "OK (fallback)")
        } else {
            primary
        }
    }

    /**
     * Test authenticated access to the remote-crawl API.
     */
    fun testAuthentication(): AuthCheckResult {
        val authCheckUrl = "$apiBase/auth-check"
        if (!hasCredentials()) {
            return AuthCheckResult(
                authenticated = false,
                statusCode = null,
                message = "Missing credentials",
                endpoint = authCheckUrl
            )
        }

        val result = requestCheck(url = authCheckUrl, includeAuth = true)
        val effective = if (!result.ok && result.statusCode == 404) {
            // Backward-compatible fallback for older servers.
            val fallbackUrl = "$apiBase/bootstrap?host=auth-check-probe"
            val fallback = requestCheck(url = fallbackUrl, includeAuth = true)
            if (fallback.ok) {
                fallback.copy(message = "OK (fallback)", endpoint = fallbackUrl)
            } else {
                fallback
            }
        } else {
            result
        }
        return AuthCheckResult(
            authenticated = effective.ok,
            statusCode = effective.statusCode,
            message = effective.message,
            endpoint = effective.endpoint
        )
    }

    /**
     * Backward-compatible wrapper used by existing command flows.
     * true means connectivity is OK and auth is either valid or not required.
     */
    fun testConnection(): Boolean {
        val connectivity = testConnectivity()
        if (!connectivity.ok) return false
        val auth = testAuthentication()
        return auth.authenticated
    }

    private fun get(url: String): String {
        log.debug("GET {}", url)

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
        withOptionalAuth(requestBuilder)
        val request = requestBuilder.build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RemoteCrawlException("GET $url failed: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun post(url: String, body: Any): String {
        log.debug("POST {}", url)

        val jsonBody = objectMapper.writeValueAsString(body)

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        withOptionalAuth(requestBuilder)
        val request = requestBuilder.build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RemoteCrawlException("POST $url failed: ${response.statusCode()} - ${response.body()}")
        }

        return response.body()
    }

    private fun withOptionalAuth(builder: HttpRequest.Builder, includeAuth: Boolean = true) {
        if (includeAuth && authHeader != null) {
            builder.header("Authorization", authHeader)
        }
    }

    private fun requestCheck(url: String, includeAuth: Boolean): ConnectivityCheckResult {
        return try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
            withOptionalAuth(requestBuilder, includeAuth = includeAuth)
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            val statusCode = response.statusCode()
            val ok = statusCode in 200..299
            val message = if (ok) "OK" else "HTTP $statusCode"
            ConnectivityCheckResult(
                ok = ok,
                statusCode = statusCode,
                message = message,
                endpoint = url
            )
        } catch (e: java.net.ConnectException) {
            ConnectivityCheckResult(ok = false, statusCode = null, message = "Connection refused", endpoint = url)
        } catch (e: java.net.UnknownHostException) {
            ConnectivityCheckResult(ok = false, statusCode = null, message = "Unknown host", endpoint = url)
        } catch (e: java.net.http.HttpTimeoutException) {
            ConnectivityCheckResult(ok = false, statusCode = null, message = "Timeout", endpoint = url)
        } catch (e: javax.net.ssl.SSLException) {
            ConnectivityCheckResult(ok = false, statusCode = null, message = "SSL error", endpoint = url)
        } catch (e: Exception) {
            ConnectivityCheckResult(
                ok = false,
                statusCode = null,
                message = "${e.javaClass.simpleName}: ${e.message ?: "request failed"}",
                endpoint = url
            )
        }
    }
}

class RemoteCrawlException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class ConnectivityCheckResult(
    val ok: Boolean,
    val statusCode: Int?,
    val message: String,
    val endpoint: String
)

data class AuthCheckResult(
    val authenticated: Boolean,
    val statusCode: Int?,
    val message: String,
    val endpoint: String
)

// ============ Request/Response DTOs ============

data class BootstrapResponse(
    val serverHost: String,
    val requestedHost: String,
    val assignments: List<CrawlConfigAssignment>
)

data class CrawlConfigAssignment(
    val crawlConfigId: Long,
    val name: String,
    val displayLabel: String,
    val description: String?,
    val sourceHost: String?,
    val targetHost: String?,
    val startPaths: List<String>,
    val maxDepth: Int,
    val followLinks: Boolean,
    val parallel: Boolean,
    val crawlMode: CrawlMode = CrawlMode.ENFORCE,
    val discoveryKeeperMaxDepth: Int = 20,
    val discoverySkipMaxDepth: Int = 10,
    val discoveryFileSampleCap: Int = 50,
    val folderPatterns: PatternSet,
    val filePatterns: PatternSet,
    val folderPatternPriority: PatternPriority = PatternPriority(),
    val filePatternPriority: PatternPriority = PatternPriority()
)

enum class CrawlMode {
    ENFORCE,
    DISCOVERY
}

data class SessionStartRequest(
    val host: String,
    val crawlConfigId: Long,
    val expectedTotal: Long? = null
)

data class SessionStartResponse(
    val sessionId: Long,
    val host: String? = null,
    val crawlConfigId: Long? = null,
    val runStatus: String? = null
)

data class SessionHeartbeatRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val currentStep: String? = null,
    val processedIncrement: Int? = null,
    val expectedTotal: Long? = null
)

data class HeartbeatResponse(
    val sessionId: Long,
    val runStatus: String? = null
)

data class IngestRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val folders: List<FolderIngestItem>? = null,
    val files: List<FileIngestItem>? = null,
    val discoveryFolders: List<DiscoveryFolderObsIngestItem>? = null,
    val discoveryFileSamples: List<DiscoveryFileSampleIngestItem>? = null,
    val processedIncrement: Int? = null
)

data class DiscoveryFolderObsIngestItem(
    val path: String,
    val depth: Int,
    val inSkipBranch: Boolean
)

data class DiscoveryFileSampleIngestItem(
    val folderPath: String,
    val sampleSlot: Int,
    val fileName: String,
    val fileSize: Long? = null
)

data class FolderIngestItem(
    val path: String,
    val analysisStatus: AnalysisStatus,
    val label: String? = null,
    val description: String? = null,
    val crawlDepth: Int? = null,
    val size: Long? = null,
    val fsLastModified: String? = null,
    val owner: String? = null,
    val group: String? = null,
    val permissions: String? = null
)

data class FileIngestItem(
    val path: String,
    val analysisStatus: AnalysisStatus,
    val label: String? = null,
    val description: String? = null,
    val crawlDepth: Int? = null,
    val size: Long? = null,
    val fsLastModified: String? = null,
    val owner: String? = null,
    val group: String? = null,
    val permissions: String? = null,
    val bodyText: String? = null,
    val bodySize: Long? = null,
    val author: String? = null,
    val title: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val comments: String? = null,
    val creationDate: String? = null,
    val modifiedDate: String? = null,
    val language: String? = null,
    val contentType: String? = null,
    val pageCount: Int? = null
)

data class IngestResponse(
    val sessionId: Long,
    val foldersReceived: Int? = null,
    val filesReceived: Int? = null,
    val foldersPersisted: Int? = null,
    val filesPersisted: Int? = null,
    val foldersNew: Long? = null,
    val foldersUpdated: Long? = null,
    val foldersSkipped: Long? = null,
    val filesNew: Long? = null,
    val filesUpdated: Long? = null,
    val filesSkipped: Long? = null,
    val filesError: Long? = null,
    val filesAccessDenied: Long? = null
)

data class SessionCompleteRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val runStatus: String = "COMPLETED",
    val errorMessage: String? = null,
    val expectedTotal: Long? = null,
    val finalStep: String? = null,
    val totals: JobRunTotals? = null
)

data class JobRunTotals(
    val filesDiscovered: Long? = null,
    val filesNew: Long? = null,
    val filesUpdated: Long? = null,
    val filesSkipped: Long? = null,
    val filesError: Long? = null,
    val filesAccessDenied: Long? = null,
    val foldersDiscovered: Long? = null,
    val foldersNew: Long? = null,
    val foldersUpdated: Long? = null,
    val foldersSkipped: Long? = null
)

data class SessionCompleteResponse(
    val sessionId: Long,
    val runStatus: String? = null
)

data class EnqueueFoldersRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val folders: List<FolderToEnqueue>,
    val defaultPriority: Int = 0
)

data class FolderToEnqueue(
    val path: String,
    val parentStatus: AnalysisStatus? = null,
    val priority: Int? = null
)

data class EnqueueFoldersResponse(
    val sessionId: Long,
    val enqueuedCount: Int,
    val skippedCount: Int,
    val status: String
)

data class TaskClaimRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val maxTasks: Int = 10,
    val reclaimAfterMinutes: Int = 10
)

data class TaskClaimResponse(
    val sessionId: Long,
    val claimToken: String?,
    val tasks: List<ClaimedTask>
)

data class ClaimedTask(
    val taskId: Long,
    val folderPath: String,
    val analysisStatus: AnalysisStatus,
    val priority: Int,
    val instructions: ProcessingInstructions?
)

data class ProcessingInstructions(
    val persistMetadata: Boolean,
    val extractText: Boolean,
    val runNlp: Boolean,
    val runEmbedding: Boolean
)

data class TaskAckRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val claimToken: String,
    val results: List<TaskAckResult>
)

data class TaskAckResult(
    val taskId: Long,
    val outcome: String,  // COMPLETED, SKIPPED, FAILED, RETRY
    val errorMessage: String? = null,
    val filesProcessed: Int? = null
)

data class TaskAckResponse(
    val sessionId: Long,
    val acknowledged: Int,
    val status: String
)

data class TaskStatusRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long
)

data class TaskStatusResponse(
    val sessionId: Long,
    val pending: Int,
    val claimed: Int,
    val completed: Int,
    val failed: Int,
    val total: Int
)

// ============ Discovery (Onboarding) DTOs ============

data class DiscoveryUploadRequest(
    val host: String,
    val folders: List<DiscoveredFolderDTO>,
    val rootPaths: List<String>,
    val osType: String,  // WINDOWS, LINUX, MACOS
    val discoveryDurationMs: Long,
    val createNewSession: Boolean = false
)

data class DiscoveredFolderDTO(
    val path: String,
    val name: String,
    val depth: Int,
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val totalSize: Long = 0,
    val isHidden: Boolean = false,
    val suggestedStatus: String? = null  // SKIP, LOCATE, INDEX, UNKNOWN
)

data class DiscoveryUploadResponse(
    val sessionId: Long,
    val host: String,
    val foldersReceived: Int,
    val classifyUrl: String,  // URL to classification UI
    val status: String
)

data class DiscoveryStatusResponse(
    val sessionId: Long,
    val host: String,
    val status: String,  // PENDING, CLASSIFIED, APPLIED
    val totalFolders: Int,
    val classifiedFolders: Int,
    val skipCount: Int,
    val locateCount: Int,
    val indexCount: Int
)

data class DiscoverySessionOptionsResponse(
    val configId: Long,
    val requestedHost: String? = null,
    val count: Int,
    val sessions: List<DiscoverySessionOption>
)

data class DiscoverySessionOption(
    val sessionId: Long,
    val host: String,
    val status: String,
    val totalFolders: Int,
    val classifiedFolders: Int,
    val dateCreated: String? = null,
    val lastUpdated: String? = null,
    val hostMatched: Boolean = false
)

// ============ Dry Run DTOs ============

data class DryRunResponse(
    val configId: Long,
    val configName: String,
    val sessionId: Long,
    val host: String,
    val detailed: Boolean,
    val summary: DryRunSummary,
    val folders: List<DryRunFolder>,
    val totalFolders: Int,
    val returnedFolders: Int,
    val truncated: Boolean,
    val durationMs: Long
)

data class DryRunSummary(
    val skip: Int,
    val locate: Int,
    val index: Int,
    val analyze: Int,
    val semantic: Int,
    val explicitCount: Int,
    val inheritedCount: Int
)

data class DryRunFolder(
    val path: String,
    val status: String,
    val explicit: Boolean,
    val matchedPattern: String?,
    val inheritedFrom: String?
)
