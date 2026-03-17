package com.oconeco.spring_search_tempo.web.service

import com.oconeco.spring_search_tempo.base.DatabaseCrawlConfigService
import com.oconeco.spring_search_tempo.base.JobRunService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.CrawlMode
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.domain.RunStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.CrawlConfigDTO
import com.oconeco.spring_search_tempo.base.model.JobRunDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.CrawlSchedulingService
import com.oconeco.spring_search_tempo.base.service.SourceHostService
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class RemoteCrawlSessionService(
    private val databaseCrawlConfigService: DatabaseCrawlConfigService,
    private val jobRunService: JobRunService,
    private val folderRepository: FSFolderRepository,
    private val fileRepository: FSFileRepository,
    private val crawlSchedulingService: CrawlSchedulingService,
    private val crawlDiscoveryObservationService: CrawlDiscoveryObservationService,
    private val sourceHostService: SourceHostService
) {
    companion object {
        private val log = LoggerFactory.getLogger(RemoteCrawlSessionService::class.java)
    }

    @Transactional
    fun start(request: RemoteSessionStartRequest): RemoteSessionStartResponse {
        val host = normalizeHost(request.host)
        val config = validateConfigForHost(host, request.crawlConfigId)

        val jobName = "remoteFsIngest-$host-${request.crawlConfigId}"
        val jobRun = jobRunService.startJobRun(request.crawlConfigId, jobName)
        request.expectedTotal?.let { if (it >= 0) jobRunService.setExpectedTotal(jobRun.id!!, it) }
        jobRunService.setCurrentStep(jobRun.id!!, "Remote crawl session started")

        if (config.crawlMode == CrawlMode.DISCOVERY) {
            crawlDiscoveryObservationService.startRun(
                crawlConfigId = request.crawlConfigId,
                host = host,
                jobRunId = jobRun.id!!
            )
        }

        return RemoteSessionStartResponse(
            sessionId = jobRun.id!!,
            host = host,
            crawlConfigId = request.crawlConfigId,
            runStatus = jobRun.runStatus
        )
    }

    @Transactional
    fun heartbeat(request: RemoteSessionHeartbeatRequest): RemoteSessionHeartbeatResponse {
        val host = normalizeHost(request.host)
        validateConfigForHost(host, request.crawlConfigId)
        val jobRun = validateSession(request.sessionId, request.crawlConfigId, requireRunning = true)

        // Heartbeat first: avoid lock inversion with other job_run updates in this transaction.
        jobRunService.updateHeartbeat(jobRun.id!!)

        request.expectedTotal?.let { if (it >= 0) jobRunService.setExpectedTotal(jobRun.id!!, it) }
        val increment = request.processedIncrement ?: 0
        val step = request.currentStep?.trim()?.ifBlank { null }
        if (increment > 0 || step != null) {
            jobRunService.updateProgress(jobRun.id!!, increment, step)
        }

        return RemoteSessionHeartbeatResponse(
            sessionId = jobRun.id!!,
            runStatus = RunStatus.RUNNING
        )
    }

    @Transactional
    fun ingest(request: RemoteIngestRequest): RemoteIngestResponse {
        val host = normalizeHost(request.host)
        val config = validateConfigForHost(host, request.crawlConfigId)
        val sourceHostRef = sourceHostService.resolveOrCreate(host)
        val jobRun = validateSession(request.sessionId, request.crawlConfigId, requireRunning = true)
        // Keep session alive before heavy writes; avoids self-deadlock later in this transaction.
        jobRunService.updateHeartbeat(jobRun.id!!)

        val dedupFolders = request.folders.orEmpty()
            .filter { it.path.isNotBlank() }
            .associateBy { toRemoteUri(host, it.path) }
            .values
            .toList()
        val dedupFiles = request.files.orEmpty()
            .filter { it.path.isNotBlank() }
            .associateBy { toRemoteUri(host, it.path) }
            .values
            .toList()

        val folderUris = dedupFolders.map { toRemoteUri(host, it.path) }
        val existingFolders = if (folderUris.isEmpty()) emptyMap() else {
            folderRepository.findByUriIn(folderUris).associateBy { it.uri!! }
        }

        val foldersToSave = mutableListOf<FSFolder>()
        var foldersNew = 0L
        var foldersUpdated = 0L
        var foldersSkipped = 0L

        for (folder in dedupFolders) {
            val uri = toRemoteUri(host, folder.path)
            val existing = existingFolders[uri]
            val entity = existing ?: FSFolder().apply {
                this.uri = uri
                this.version = 0L
                this.type = "FOLDER"
                this.status = Status.NEW
            }

            val status = folder.analysisStatus ?: AnalysisStatus.LOCATE
            entity.status = if (existing == null) Status.NEW else Status.CURRENT
            entity.analysisStatus = status
            entity.type = "FOLDER"
            entity.uri = uri
            entity.label = folder.label?.takeIf { it.isNotBlank() } ?: leafName(folder.path)
            entity.description = folder.description
            entity.crawlDepth = folder.crawlDepth
            entity.size = folder.size
            entity.fsLastModified = folder.fsLastModified
            entity.owner = folder.owner
            entity.group = folder.group
            entity.permissions = folder.permissions
            entity.crawlConfigId = request.crawlConfigId
            entity.jobRunId = jobRun.id
            entity.sourceHost = host
            entity.sourceHostRef = sourceHostRef

            if (status == AnalysisStatus.SKIP) {
                foldersSkipped++
            } else if (existing == null) {
                foldersNew++
            } else {
                foldersUpdated++
            }
            foldersToSave.add(entity)
        }

        val persistedFolders = if (foldersToSave.isEmpty()) {
            emptyList()
        } else {
            folderRepository.saveAll(foldersToSave)
        }
        val persistedFolderByUri = persistedFolders.associateBy { it.uri!! }

        val fileUris = dedupFiles.map { toRemoteUri(host, it.path) }
        val existingFiles = if (fileUris.isEmpty()) emptyMap() else {
            fileRepository.findByUriIn(fileUris).associateBy { it.uri!! }
        }

        val parentUris = dedupFiles.mapNotNull { file ->
            parentPath(file.path)?.let { toRemoteUri(host, it) }
        }.distinct()
        val parentFolders = if (parentUris.isEmpty()) {
            emptyMap()
        } else {
            folderRepository.findByUriIn(parentUris).associateBy { it.uri!! }
        }

        val allKnownFolders = HashMap<String, FSFolder>()
        allKnownFolders.putAll(parentFolders)
        allKnownFolders.putAll(persistedFolderByUri)

        val filesToSave = mutableListOf<FSFile>()
        var filesNew = 0L
        var filesUpdated = 0L
        var filesSkipped = 0L
        var filesError = 0L
        var filesAccessDenied = 0L

        for (file in dedupFiles) {
            val uri = toRemoteUri(host, file.path)
            val existing = existingFiles[uri]
            val entity = existing ?: FSFile().apply {
                this.uri = uri
                this.version = 0L
                this.type = "FILE"
                this.status = Status.NEW
            }

            val status = file.analysisStatus ?: AnalysisStatus.LOCATE
            entity.status = if (existing == null) Status.NEW else Status.CURRENT
            entity.analysisStatus = status
            entity.type = "FILE"
            entity.uri = uri
            entity.label = file.label?.takeIf { it.isNotBlank() } ?: leafName(file.path)
            entity.description = file.description
            entity.crawlDepth = file.crawlDepth
            entity.size = file.size
            entity.fsLastModified = file.fsLastModified
            entity.owner = file.owner
            entity.group = file.group
            entity.permissions = file.permissions
            entity.crawlConfigId = request.crawlConfigId
            entity.jobRunId = jobRun.id
            entity.sourceHost = host
            entity.sourceHostRef = sourceHostRef

            entity.bodyText = file.bodyText
            entity.bodySize = file.bodySize ?: file.bodyText?.length?.toLong()
            entity.author = file.author
            entity.title = file.title
            entity.subject = file.subject
            entity.keywords = file.keywords
            entity.comments = file.comments
            entity.creationDate = file.creationDate
            entity.modifiedDate = file.modifiedDate
            entity.language = file.language
            entity.contentType = file.contentType
            entity.pageCount = file.pageCount

            val parentUri = parentPath(file.path)?.let { toRemoteUri(host, it) }
            entity.fsFolder = parentUri?.let { allKnownFolders[it] }

            if (status == AnalysisStatus.SKIP) {
                filesSkipped++
            } else if (existing == null) {
                filesNew++
            } else {
                filesUpdated++
            }
            if (file.extractionError == true) {
                filesError++
            }
            if (file.accessDenied == true) {
                filesAccessDenied++
            }

            filesToSave.add(entity)
        }

        if (filesToSave.isNotEmpty()) {
            fileRepository.saveAll(filesToSave)
        }

        if (config.crawlMode == CrawlMode.DISCOVERY) {
            crawlDiscoveryObservationService.ingest(
                crawlConfigId = request.crawlConfigId,
                host = host,
                jobRunId = jobRun.id!!,
                folders = request.discoveryFolders.orEmpty(),
                fileSamples = request.discoveryFileSamples.orEmpty(),
                sampleCap = config.discoveryFileSampleCap
            )
        }

        // Track folder modification times for temperature calculation (smart crawl)
        updateFolderTemperatures(
            host = host,
            crawlConfigId = request.crawlConfigId,
            filesToSave = filesToSave,
            existingFiles = existingFiles,
            persistedFolderByUri = persistedFolderByUri
        )

        val current = jobRunService.get(jobRun.id!!)
        val foldersDiscoveredIncrement = dedupFolders.size.toLong()
        val filesDiscoveredIncrement = dedupFiles.size.toLong()
        jobRunService.updateJobRunStats(
            jobRunId = jobRun.id!!,
            filesDiscovered = current.filesDiscovered + filesDiscoveredIncrement,
            filesNew = current.filesNew + filesNew,
            filesUpdated = current.filesUpdated + filesUpdated,
            filesSkipped = current.filesSkipped + filesSkipped,
            filesError = current.filesError + filesError,
            filesAccessDenied = current.filesAccessDenied + filesAccessDenied,
            foldersDiscovered = current.foldersDiscovered + foldersDiscoveredIncrement,
            foldersNew = current.foldersNew + foldersNew,
            foldersUpdated = current.foldersUpdated + foldersUpdated,
            foldersSkipped = current.foldersSkipped + foldersSkipped
        )

        val processedIncrement = request.processedIncrement
            ?: (dedupFolders.size + dedupFiles.size)
        if (processedIncrement > 0) {
            jobRunService.updateProgress(jobRun.id!!, processedIncrement, "Remote ingest batch")
        }

        return RemoteIngestResponse(
            sessionId = jobRun.id!!,
            foldersReceived = dedupFolders.size,
            filesReceived = dedupFiles.size,
            foldersPersisted = foldersToSave.size,
            filesPersisted = filesToSave.size,
            foldersNew = foldersNew,
            foldersUpdated = foldersUpdated,
            foldersSkipped = foldersSkipped,
            filesNew = filesNew,
            filesUpdated = filesUpdated,
            filesSkipped = filesSkipped,
            filesError = filesError,
            filesAccessDenied = filesAccessDenied
        )
    }

    @Transactional
    fun complete(request: RemoteSessionCompleteRequest): RemoteSessionCompleteResponse {
        val host = normalizeHost(request.host)
        val config = validateConfigForHost(host, request.crawlConfigId)
        val jobRun = validateSession(request.sessionId, request.crawlConfigId, requireRunning = false)

        request.expectedTotal?.let { if (it >= 0) jobRunService.setExpectedTotal(jobRun.id!!, it) }
        request.finalStep?.trim()?.ifBlank { null }?.let { jobRunService.setCurrentStep(jobRun.id!!, it) }
        request.totals?.let { totals ->
            jobRunService.updateJobRunStats(
                jobRunId = jobRun.id!!,
                filesDiscovered = totals.filesDiscovered,
                filesNew = totals.filesNew,
                filesUpdated = totals.filesUpdated,
                filesSkipped = totals.filesSkipped,
                filesError = totals.filesError,
                filesAccessDenied = totals.filesAccessDenied,
                foldersDiscovered = totals.foldersDiscovered,
                foldersNew = totals.foldersNew,
                foldersUpdated = totals.foldersUpdated,
                foldersSkipped = totals.foldersSkipped
            )
        }
        jobRunService.completeJobRun(jobRun.id!!, request.runStatus, request.errorMessage)

        if (config.crawlMode == CrawlMode.DISCOVERY) {
            crawlDiscoveryObservationService.completeRun(jobRun.id!!, request.runStatus)
            val reapply = crawlDiscoveryObservationService.reapplySkipRules(
                crawlConfigId = request.crawlConfigId,
                host = host,
                jobRunId = jobRun.id
            )
            val suggestEnforce = crawlDiscoveryObservationService.shouldSuggestEnforce(
                crawlConfigId = request.crawlConfigId,
                host = host
            )
            log.info(
                "Discovery reapply complete for config {} host {}: changed {} of {} observations (suggestEnforce={})",
                request.crawlConfigId, host, reapply.changed, reapply.total, suggestEnforce
            )
        }

        return RemoteSessionCompleteResponse(
            sessionId = jobRun.id!!,
            runStatus = request.runStatus
        )
    }

    private fun validateConfigForHost(host: String, crawlConfigId: Long): CrawlConfigDTO {
        val config = databaseCrawlConfigService.get(crawlConfigId)
        val sourceHost = config.sourceHost?.trim()?.takeIf { it.isNotBlank() }
        if (sourceHost != null) {
            val normalizedSourceHost = normalizeHost(sourceHost)
            require(normalizedSourceHost == host) {
                "crawl config $crawlConfigId is not assigned to host '$host' (sourceHost='$sourceHost')"
            }
        }
        return config
    }

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
        require(normalized.isNotBlank()) { "path is required" }
        normalized = normalized.replace(Regex("/{2,}"), "/")
        if (!normalized.startsWith("/")) {
            normalized = "/$normalized"
        }
        if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized = normalized.removeSuffix("/")
        }
        return normalized
    }

    private fun toRemoteUri(host: String, rawPath: String): String {
        val path = normalizePath(rawPath)
        return "remote://$host$path"
    }

    private fun leafName(rawPath: String): String {
        val path = normalizePath(rawPath)
        return path.substringAfterLast('/')
            .ifBlank { path }
    }

    private fun parentPath(rawPath: String): String? {
        val path = normalizePath(rawPath)
        val idx = path.lastIndexOf('/')
        if (idx <= 0) return null
        return path.substring(0, idx)
    }

    /**
     * Track folder modifications for smart crawl temperature updates.
     *
     * Compares incoming files with existing files to detect changes,
     * then updates folder temperatures based on detected activity.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun updateFolderTemperatures(
        host: String, // Reserved for future per-host temperature thresholds
        crawlConfigId: Long,
        filesToSave: List<FSFile>,
        existingFiles: Map<String, FSFile>,
        persistedFolderByUri: Map<String, FSFolder>
    ) {
        // Check if smart crawl is enabled for this config
        val config = try {
            databaseCrawlConfigService.get(crawlConfigId)
        } catch (e: Exception) {
            log.warn("Could not check smart crawl config {}: {}", crawlConfigId, e.message)
            return
        }

        if (config.smartCrawlEnabled != true) {
            return // Skip temperature tracking if not enabled
        }

        val hotThresholdDays = config.hotThresholdDays
            ?: CrawlSchedulingService.DEFAULT_HOT_THRESHOLD_DAYS
        val warmThresholdDays = config.warmThresholdDays
            ?: CrawlSchedulingService.DEFAULT_WARM_THRESHOLD_DAYS

        // Track which folders had changes detected
        val folderChanges = mutableMapOf<Long, Boolean>()
        val folderMaxMtime = mutableMapOf<Long, OffsetDateTime>()

        for (file in filesToSave) {
            val folderId = file.fsFolder?.id ?: continue
            val existingFile = existingFiles[file.uri]

            // Detect change: new file or modified timestamp changed
            val isChanged = existingFile == null ||
                existingFile.fsLastModified != file.fsLastModified ||
                existingFile.size != file.size

            if (isChanged) {
                folderChanges[folderId] = true
            } else if (!folderChanges.containsKey(folderId)) {
                folderChanges[folderId] = false
            }

            // Track max mtime per folder for childModifiedAt update
            file.fsLastModified?.let { mtime ->
                val currentMax = folderMaxMtime[folderId]
                if (currentMax == null || mtime.isAfter(currentMax)) {
                    folderMaxMtime[folderId] = mtime
                }
            }
        }

        // Also track folders that were ingested (even if no files changed)
        for (folder in persistedFolderByUri.values) {
            val folderId = folder.id ?: continue
            if (!folderChanges.containsKey(folderId)) {
                folderChanges[folderId] = false
            }
        }

        if (folderChanges.isEmpty()) {
            return
        }

        // Update temperatures for each affected folder
        for ((folderId, hasChanges) in folderChanges) {
            try {
                crawlSchedulingService.updateTemperatureAfterCrawl(
                    folderId = folderId,
                    changesDetected = hasChanges,
                    mostRecentChildMtime = folderMaxMtime[folderId],
                    hotThresholdDays = hotThresholdDays,
                    warmThresholdDays = warmThresholdDays
                )
            } catch (e: Exception) {
                log.warn("Failed to update temperature for folder {}: {}", folderId, e.message)
            }
        }

        val changedCount = folderChanges.values.count { it }
        log.debug(
            "Updated temperatures for {} folders ({} with changes)",
            folderChanges.size, changedCount
        )
    }
}

data class RemoteSessionStartRequest(
    val host: String,
    val crawlConfigId: Long,
    val expectedTotal: Long? = null
)

data class RemoteSessionStartResponse(
    val sessionId: Long,
    val host: String,
    val crawlConfigId: Long,
    val runStatus: RunStatus
)

data class RemoteSessionHeartbeatRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val processedIncrement: Int? = null,
    val currentStep: String? = null,
    val expectedTotal: Long? = null
)

data class RemoteSessionHeartbeatResponse(
    val sessionId: Long,
    val runStatus: RunStatus
)

data class RemoteIngestRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val folders: List<RemoteFolderIngestItem>? = null,
    val files: List<RemoteFileIngestItem>? = null,
    val discoveryFolders: List<RemoteDiscoveryFolderObsIngestItem>? = null,
    val discoveryFileSamples: List<RemoteDiscoveryFileSampleIngestItem>? = null,
    val processedIncrement: Int? = null
)

data class RemoteDiscoveryFolderObsIngestItem(
    val path: String,
    val depth: Int,
    val inSkipBranch: Boolean
)

data class RemoteDiscoveryFileSampleIngestItem(
    val folderPath: String,
    val sampleSlot: Int,
    val fileName: String,
    val fileSize: Long? = null
)

data class RemoteFolderIngestItem(
    val path: String,
    val parentStatus: AnalysisStatus? = null,
    val analysisStatus: AnalysisStatus? = AnalysisStatus.LOCATE,
    val label: String? = null,
    val description: String? = null,
    val crawlDepth: Int? = null,
    val size: Long? = null,
    val fsLastModified: OffsetDateTime? = null,
    val owner: String? = null,
    val group: String? = null,
    val permissions: String? = null
)

data class RemoteFileIngestItem(
    val path: String,
    val analysisStatus: AnalysisStatus? = AnalysisStatus.LOCATE,
    val label: String? = null,
    val description: String? = null,
    val crawlDepth: Int? = null,
    val size: Long? = null,
    val fsLastModified: OffsetDateTime? = null,
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
    val pageCount: Int? = null,
    val accessDenied: Boolean? = null,
    val extractionError: Boolean? = null
)

data class RemoteIngestResponse(
    val sessionId: Long,
    val foldersReceived: Int,
    val filesReceived: Int,
    val foldersPersisted: Int,
    val filesPersisted: Int,
    val foldersNew: Long,
    val foldersUpdated: Long,
    val foldersSkipped: Long,
    val filesNew: Long,
    val filesUpdated: Long,
    val filesSkipped: Long,
    val filesError: Long,
    val filesAccessDenied: Long
)

data class RemoteSessionCompleteRequest(
    val host: String,
    val crawlConfigId: Long,
    val sessionId: Long,
    val runStatus: RunStatus = RunStatus.COMPLETED,
    val errorMessage: String? = null,
    val expectedTotal: Long? = null,
    val finalStep: String? = null,
    val totals: RemoteJobRunTotals? = null
)

data class RemoteJobRunTotals(
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

data class RemoteSessionCompleteResponse(
    val sessionId: Long,
    val runStatus: RunStatus
)
