package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.RemoteCrawlTask
import com.oconeco.spring_search_tempo.base.domain.RemoteTaskStatus
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.domain.HostCrawlSessionFolderStatus
import com.oconeco.spring_search_tempo.base.model.JobRunDTO
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.repos.RemoteCrawlTaskRepository
import com.oconeco.spring_search_tempo.base.service.SourceHostService
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RemoteCrawlTaskService(
    private val databaseCrawlConfigService: DatabaseCrawlConfigService,
    private val jobRunService: JobRunService,
    private val remoteCrawlSessionService: RemoteCrawlSessionService,
    private val remoteCrawlPlannerService: RemoteCrawlPlannerService,
    private val folderRepository: FSFolderRepository,
    private val remoteCrawlTaskRepository: RemoteCrawlTaskRepository,
    private val sourceHostService: SourceHostService,
    private val hostCrawlSessionService: HostCrawlSessionService
) {

    @Transactional
    fun enqueueFolders(request: RemoteEnqueueFoldersRequest): RemoteEnqueueFoldersResponse {
        val host = normalizeHost(request.host)
        validateConfigForHost(host, request.crawlConfigId)
        validateSession(request.sessionId, request.crawlConfigId, requireRunning = true)
        val sourceHostRef = sourceHostService.resolveOrCreate(host)

        val deduped = request.folders
            .filter { it.path.isNotBlank() }
            .associateBy { toRemoteUri(host, it.path) }
            .values
            .toList()
        if (deduped.isEmpty()) {
            return RemoteEnqueueFoldersResponse(
                sessionId = request.sessionId,
                received = 0,
                queued = 0,
                skipped = 0,
                duplicates = 0
            )
        }

        val classification = remoteCrawlPlannerService.classify(
            RemoteClassifyRequest(
                host = host,
                crawlConfigId = request.crawlConfigId,
                folders = deduped.map { RemoteFolderPath(path = it.path, parentStatus = it.parentStatus) }
            )
        )
        val statusByRemoteUri = classification.folders.associate { toRemoteUri(host, it.path) to it.analysisStatus }

        val classifiedFolders = deduped.map { folder ->
            val status = statusByRemoteUri[toRemoteUri(host, folder.path)] ?: AnalysisStatus.LOCATE
            folder.copy(analysisStatus = status)
        }

        remoteCrawlSessionService.ingest(
            RemoteIngestRequest(
                host = host,
                crawlConfigId = request.crawlConfigId,
                sessionId = request.sessionId,
                folders = classifiedFolders,
                files = emptyList(),
                processedIncrement = request.processedIncrement
            )
        )

        val remoteUris = classifiedFolders.map { toRemoteUri(host, it.path) }
        val existingByUri = remoteCrawlTaskRepository
            .findBySessionIdAndRemoteUriIn(request.sessionId, remoteUris)
            .associateBy { it.remoteUri!! }

        val toCreate = mutableListOf<RemoteCrawlTask>()
        var skipped = 0
        var duplicates = 0
        for (folder in classifiedFolders) {
            val uri = toRemoteUri(host, folder.path)
            val status = folder.analysisStatus ?: AnalysisStatus.LOCATE
            if (status == AnalysisStatus.SKIP) {
                skipped++
                continue
            }
            if (existingByUri.containsKey(uri)) {
                duplicates++
                continue
            }

            val task = RemoteCrawlTask().apply {
                sessionId = request.sessionId
                crawlConfigId = request.crawlConfigId
                this.host = host
                this.sourceHostRef = sourceHostRef
                folderPath = normalizePath(folder.path)
                remoteUri = uri
                analysisStatus = status
                taskStatus = RemoteTaskStatus.PENDING
                depth = folder.crawlDepth ?: depthFromPath(folder.path)
                priority = request.defaultPriority
            }
            toCreate.add(task)
        }

        if (toCreate.isNotEmpty()) {
            val savedTasks = remoteCrawlTaskRepository.saveAll(toCreate)
            val folderByUri = classifiedFolders.associateBy { toRemoteUri(host, it.path) }
            val persistedFoldersByUri = folderRepository.findByUriIn(savedTasks.map { it.remoteUri!! })
                .associateBy { it.uri!! }
            hostCrawlSessionService.recordQueuedFolders(
                jobRunId = request.sessionId,
                queueItems = savedTasks.map { task ->
                    val folder = folderByUri[task.remoteUri!!]
                    QueuedFolderSelection(
                        selectedPath = task.folderPath!!,
                        analysisStatus = task.analysisStatus,
                        fsFolder = persistedFoldersByUri[task.remoteUri!!],
                        remoteCrawlTaskId = task.id,
                        selectionReasonDetail = buildString {
                            append("host=").append(host)
                            append(", priority=").append(task.priority)
                            folder?.parentStatus?.let { append(", parentStatus=").append(it) }
                        }
                    )
                }
            )
        }
        jobRunService.setCurrentStep(request.sessionId, "Remote folder queue: ${toCreate.size} pending")

        return RemoteEnqueueFoldersResponse(
            sessionId = request.sessionId,
            received = classifiedFolders.size,
            queued = toCreate.size,
            skipped = skipped,
            duplicates = duplicates
        )
    }

    @Transactional
    fun claimNext(request: RemoteTaskClaimRequest): RemoteTaskClaimResponse {
        val host = normalizeHost(request.host)
        validateConfigForHost(host, request.crawlConfigId)
        validateSession(request.sessionId, request.crawlConfigId, requireRunning = true)

        val maxTasks = request.maxTasks.coerceIn(1, 500)
        val staleBefore = OffsetDateTime.now().minusMinutes(request.reclaimAfterMinutes.coerceIn(1, 240).toLong())
        val reclaimed = remoteCrawlTaskRepository.releaseStaleClaims(
            sessionId = request.sessionId,
            staleBefore = staleBefore,
            claimedStatus = RemoteTaskStatus.CLAIMED,
            pendingStatus = RemoteTaskStatus.PENDING
        )

        val ids = remoteCrawlTaskRepository.findPendingIdsForClaim(request.sessionId, maxTasks)
        if (ids.isEmpty()) {
            return RemoteTaskClaimResponse(
                sessionId = request.sessionId,
                claimToken = null,
                reclaimed = reclaimed,
                tasks = emptyList(),
                queueStatus = queueStatus(request.sessionId)
            )
        }

        val claimToken = UUID.randomUUID().toString()
        val now = OffsetDateTime.now()
        var claimedCount = 0
        for (id in ids) {
            claimedCount += remoteCrawlTaskRepository.claimTask(
                id = id,
                claimToken = claimToken,
                claimedAt = now,
                pendingStatus = RemoteTaskStatus.PENDING,
                claimedStatus = RemoteTaskStatus.CLAIMED
            )
        }

        if (claimedCount == 0) {
            return RemoteTaskClaimResponse(
                sessionId = request.sessionId,
                claimToken = null,
                reclaimed = reclaimed,
                tasks = emptyList(),
                queueStatus = queueStatus(request.sessionId)
            )
        }

        val tasks = remoteCrawlTaskRepository
            .findBySessionIdAndClaimTokenAndTaskStatusOrderByPriorityDescDepthAscIdAsc(
                sessionId = request.sessionId,
                claimToken = claimToken,
                taskStatus = RemoteTaskStatus.CLAIMED
            )
            .map { task ->
                RemoteTaskAssignment(
                    taskId = task.id!!,
                    folderPath = task.folderPath!!,
                    remoteUri = task.remoteUri!!,
                    analysisStatus = task.analysisStatus,
                    instructions = remoteCrawlPlannerService.instructionsFor(task.analysisStatus),
                    depth = task.depth,
                    priority = task.priority
                )
            }

        hostCrawlSessionService.markClaimed(
            jobRunId = request.sessionId,
            remoteTaskIds = tasks.map { it.taskId },
            claimedAt = now
        )

        jobRunService.setCurrentStep(request.sessionId, "Remote folder queue: claimed ${tasks.size}")

        return RemoteTaskClaimResponse(
            sessionId = request.sessionId,
            claimToken = claimToken,
            reclaimed = reclaimed,
            tasks = tasks,
            queueStatus = queueStatus(request.sessionId)
        )
    }

    @Transactional
    fun ack(request: RemoteTaskAckRequest): RemoteTaskAckResponse {
        val host = normalizeHost(request.host)
        validateConfigForHost(host, request.crawlConfigId)
        validateSession(request.sessionId, request.crawlConfigId, requireRunning = true)
        require(request.claimToken.isNotBlank()) { "claimToken is required" }

        if (request.results.isEmpty()) {
            return RemoteTaskAckResponse(
                sessionId = request.sessionId,
                updated = 0,
                ignored = 0,
                completed = 0,
                skipped = 0,
                failed = 0,
                retried = 0,
                queueStatus = queueStatus(request.sessionId)
            )
        }

        val ids = request.results.map { it.taskId }.distinct()
        val tasksById = remoteCrawlTaskRepository
            .findBySessionIdAndClaimTokenAndIdIn(request.sessionId, request.claimToken, ids)
            .associateBy { it.id!! }
            .toMutableMap()

        val now = OffsetDateTime.now()
        var updated = 0
        var ignored = 0
        var completed = 0
        var skipped = 0
        var failed = 0
        var retried = 0

        for (result in request.results) {
            val task = tasksById[result.taskId]
            if (task == null || task.taskStatus != RemoteTaskStatus.CLAIMED) {
                ignored++
                continue
            }

            when (result.outcome) {
                RemoteTaskOutcome.COMPLETED -> {
                    task.taskStatus = RemoteTaskStatus.COMPLETED
                    task.completedAt = now
                    task.claimToken = null
                    task.claimedAt = null
                    task.lastError = null
                    completed++
                }
                RemoteTaskOutcome.SKIPPED -> {
                    task.taskStatus = RemoteTaskStatus.SKIPPED
                    task.completedAt = now
                    task.claimToken = null
                    task.claimedAt = null
                    task.lastError = result.errorMessage
                    skipped++
                }
                RemoteTaskOutcome.FAILED -> {
                    task.taskStatus = RemoteTaskStatus.FAILED
                    task.completedAt = now
                    task.claimToken = null
                    task.claimedAt = null
                    task.lastError = result.errorMessage
                    failed++
                }
                RemoteTaskOutcome.RETRY -> {
                    task.taskStatus = RemoteTaskStatus.PENDING
                    task.completedAt = null
                    task.claimToken = null
                    task.claimedAt = null
                    task.lastError = result.errorMessage
                    retried++
                }
            }
            updated++
        }

        if (updated > 0) {
            remoteCrawlTaskRepository.saveAll(tasksById.values)
            hostCrawlSessionService.applyResults(
                jobRunId = request.sessionId,
                results = request.results.map { result ->
                    QueuedFolderResult(
                        remoteCrawlTaskId = result.taskId,
                        status = when (result.outcome) {
                            RemoteTaskOutcome.COMPLETED -> HostCrawlSessionFolderStatus.COMPLETED
                            RemoteTaskOutcome.SKIPPED -> HostCrawlSessionFolderStatus.SKIPPED
                            RemoteTaskOutcome.FAILED -> HostCrawlSessionFolderStatus.FAILED
                            RemoteTaskOutcome.RETRY -> HostCrawlSessionFolderStatus.RETRY
                        },
                        errorMessage = result.errorMessage
                    )
                }
            )
        }
        request.processedIncrement?.let { if (it > 0) jobRunService.updateProgress(request.sessionId, it, "Remote queue ack") }

        return RemoteTaskAckResponse(
            sessionId = request.sessionId,
            updated = updated,
            ignored = ignored,
            completed = completed,
            skipped = skipped,
            failed = failed,
            retried = retried,
            queueStatus = queueStatus(request.sessionId)
        )
    }

    @Transactional(readOnly = true)
    fun status(request: RemoteTaskStatusRequest): RemoteTaskStatusResponse {
        val host = normalizeHost(request.host)
        validateConfigForHost(host, request.crawlConfigId)
        validateSession(request.sessionId, request.crawlConfigId, requireRunning = false)
        return RemoteTaskStatusResponse(
            sessionId = request.sessionId,
            queueStatus = queueStatus(request.sessionId)
        )
    }

    private fun queueStatus(sessionId: Long): Map<String, Long> {
        val counts = RemoteTaskStatus.entries.associate { it.name to 0L }.toMutableMap()
        remoteCrawlTaskRepository.countBySessionGroupedStatus(sessionId).forEach { row ->
            val status = row[0] as RemoteTaskStatus
            val count = row[1] as Long
            counts[status.name] = count
        }
        return counts
    }

    @Suppress("UNUSED_PARAMETER")
    private fun validateConfigForHost(host: String, crawlConfigId: Long) =
        databaseCrawlConfigService.get(crawlConfigId)

    private fun validateSession(sessionId: Long, crawlConfigId: Long, requireRunning: Boolean): JobRunDTO {
        val run = try {
            jobRunService.get(sessionId)
        } catch (_: NotFoundException) {
            throw NotFoundException("remote session not found: $sessionId")
        }

        if (run.crawlConfig != crawlConfigId) {
            throw IllegalArgumentException(
                "session $sessionId belongs to crawlConfig=${run.crawlConfig}, not $crawlConfigId"
            )
        }
        if (requireRunning && run.runStatus != RunStatus.RUNNING) {
            throw IllegalArgumentException("session $sessionId is not RUNNING (status=${run.runStatus})")
        }
        return run
    }

    private fun normalizeHost(host: String): String {
        val trimmed = host.trim().lowercase()
        require(trimmed.isNotBlank()) { "host is required" }
        return trimmed.replace(Regex("[^a-z0-9._-]"), "-")
    }

    private fun normalizePath(rawPath: String): String {
        var normalized = rawPath.trim().replace('\\', '/')
        normalized = normalized.replace(Regex("/{2,}"), "/")
        if (!normalized.startsWith("/")) {
            normalized = "/$normalized"
        }
        if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized = normalized.removeSuffix("/")
        }
        return normalized
    }

    private fun toRemoteUri(host: String, rawPath: String): String =
        "remote://$host${normalizePath(rawPath)}"

    private fun depthFromPath(rawPath: String): Int {
        val path = normalizePath(rawPath)
        return path.split('/').count { it.isNotBlank() }
    }
}

data class RemoteEnqueueFoldersRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val folders: List<RemoteFolderIngestItem>,
    val defaultPriority: Int = 0,
    val processedIncrement: Int? = null
)

data class RemoteEnqueueFoldersResponse(
    val sessionId: Long,
    val received: Int,
    val queued: Int,
    val skipped: Int,
    val duplicates: Int
)

data class RemoteTaskClaimRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val maxTasks: Int = 50,
    val reclaimAfterMinutes: Int = 10
)

data class RemoteTaskClaimResponse(
    val sessionId: Long,
    val claimToken: String?,
    val reclaimed: Int,
    val tasks: List<RemoteTaskAssignment>,
    val queueStatus: Map<String, Long>
)

data class RemoteTaskAssignment(
    val taskId: Long,
    val folderPath: String,
    val remoteUri: String,
    val analysisStatus: AnalysisStatus,
    val instructions: RemoteProcessingInstructions,
    val depth: Int?,
    val priority: Int
)

data class RemoteTaskAckRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val claimToken: String,
    val results: List<RemoteTaskAckItem>,
    val processedIncrement: Int? = null
)

data class RemoteTaskAckItem(
    val taskId: Long,
    val outcome: RemoteTaskOutcome,
    val errorMessage: String? = null
)

enum class RemoteTaskOutcome {
    COMPLETED,
    SKIPPED,
    FAILED,
    RETRY
}

data class RemoteTaskAckResponse(
    val sessionId: Long,
    val updated: Int,
    val ignored: Int,
    val completed: Int,
    val skipped: Int,
    val failed: Int,
    val retried: Int,
    val queueStatus: Map<String, Long>
)

data class RemoteTaskStatusRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long
)

data class RemoteTaskStatusResponse(
    val sessionId: Long,
    val queueStatus: Map<String, Long>
)
