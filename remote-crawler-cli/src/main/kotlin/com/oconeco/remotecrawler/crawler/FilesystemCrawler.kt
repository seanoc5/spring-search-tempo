package com.oconeco.remotecrawler.crawler

import com.oconeco.remotecrawler.client.*
import com.oconeco.remotecrawler.extraction.TextAndMetadataResult
import com.oconeco.remotecrawler.extraction.TextExtractionService
import com.oconeco.remotecrawler.model.AnalysisStatus
import com.oconeco.remotecrawler.model.EffectivePatterns
import com.oconeco.remotecrawler.pattern.PatternMatchingService
import com.oconeco.remotecrawler.util.FileSystemMetadata
import com.oconeco.remotecrawler.util.PathUtils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Filesystem crawler that walks directories and sends results to the server.
 *
 * Flow:
 * 1. Walk filesystem starting from configured paths
 * 2. Apply SKIP patterns during walk (SKIP_SUBTREE optimization)
 * 3. Batch folders/files and send to server for full classification
 * 4. For INDEX+ files: extract text with Tika
 * 5. Ingest results to server in batches
 */
class FilesystemCrawler(
    private val client: RemoteCrawlClient,
    private val textExtractor: TextExtractionService = TextExtractionService(),
    private val patternMatcher: PatternMatchingService = PatternMatchingService(),
    private val batchSize: Int = 100,
    private val adaptiveBatching: Boolean = false,
    private val heartbeatIntervalMs: Long = 30_000
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Run a crawl for the given configuration.
     */
    fun crawl(config: CrawlConfigAssignment, host: String): CrawlResult {
        log.info("Starting crawl for config '{}' with {} start paths",
            config.name, config.startPaths.size)

        val startTime = System.currentTimeMillis()
        val stats = CrawlStats()

        // Start server session
        val sessionResponse = client.startSession(
            SessionStartRequest(
                host = host,
                crawlConfigId = config.crawlConfigId
            )
        )
        val sessionId = sessionResponse.sessionId
        log.info("Started session {} for config {}", sessionId, config.crawlConfigId)

        val batchController = AdaptiveBatchController(
            initialItems = batchSize,
            minItems = (batchSize / 2).coerceAtLeast(25),
            maxItems = (batchSize * 40).coerceIn(200, 10_000),
            targetPayloadBytes = if (adaptiveBatching) 8L * 1024 * 1024 else Long.MAX_VALUE,
            adaptiveEnabled = adaptiveBatching
        )
        log.info(
            "Batching mode: {} (initial={}, min={}, max={}, payloadTarget={}MB)",
            if (adaptiveBatching) "adaptive" else "fixed",
            batchController.currentItems(),
            batchController.minItems,
            batchController.maxItems,
            if (batchController.targetPayloadBytes == Long.MAX_VALUE) "unbounded"
            else (batchController.targetPayloadBytes / (1024 * 1024)).toString()
        )

        val effectivePatterns = EffectivePatterns(
            folderPatterns = config.folderPatterns,
            filePatterns = config.filePatterns,
            folderPatternPriority = config.folderPatternPriority,
            filePatternPriority = config.filePatternPriority
        )

        try {
            // Process each start path
            val startPaths = config.startPaths.map { Paths.get(it) }

            for (startPath in startPaths) {
                if (!Files.exists(startPath)) {
                    log.warn("Start path does not exist: {}", startPath)
                    stats.errors.incrementAndGet()
                    continue
                }

                log.info("Crawling start path: {}", startPath)
                crawlPath(
                    startPath = startPath,
                    allStartPaths = startPaths,
                    config = config,
                    effectivePatterns = effectivePatterns,
                    sessionId = sessionId,
                    host = host,
                    maxDepth = config.maxDepth,
                    followLinks = config.followLinks,
                    stats = stats,
                    batchController = batchController
                )
            }

            // Complete session
            client.completeSession(
                SessionCompleteRequest(
                    host = host,
                    crawlConfigId = config.crawlConfigId,
                    sessionId = sessionId,
                    runStatus = "COMPLETED",
                    finalStep = "Crawl complete",
                    totals = JobRunTotals(
                        filesDiscovered = stats.filesProcessed.get().toLong(),
                        foldersDiscovered = stats.foldersProcessed.get().toLong(),
                        filesError = stats.errors.get().toLong()
                    )
                )
            )

            val duration = System.currentTimeMillis() - startTime
            log.info("Crawl completed in {}ms - {} folders, {} files, {} errors",
                duration, stats.foldersProcessed.get(), stats.filesProcessed.get(), stats.errors.get())

            return CrawlResult(
                sessionId = sessionId,
                foldersProcessed = stats.foldersProcessed.get(),
                filesProcessed = stats.filesProcessed.get(),
                bytesProcessed = stats.bytesProcessed.get(),
                errors = stats.errors.get(),
                durationMs = duration,
                success = true
            )

        } catch (e: Exception) {
            log.error("Crawl failed", e)

            // Try to complete session with error status
            try {
                client.completeSession(
                    SessionCompleteRequest(
                        host = host,
                        crawlConfigId = config.crawlConfigId,
                        sessionId = sessionId,
                        runStatus = "FAILED",
                        finalStep = "Error: ${e.message}"
                    )
                )
            } catch (ignore: Exception) {
                log.warn("Failed to complete session with error status")
            }

            return CrawlResult(
                sessionId = sessionId,
                foldersProcessed = stats.foldersProcessed.get(),
                filesProcessed = stats.filesProcessed.get(),
                bytesProcessed = stats.bytesProcessed.get(),
                errors = stats.errors.get(),
                durationMs = System.currentTimeMillis() - startTime,
                success = false,
                errorMessage = e.message
            )
        }
    }

    private fun crawlPath(
        startPath: Path,
        allStartPaths: List<Path>,
        config: CrawlConfigAssignment,
        effectivePatterns: EffectivePatterns,
        sessionId: Long,
        host: String,
        maxDepth: Int,
        followLinks: Boolean,
        stats: CrawlStats,
        batchController: AdaptiveBatchController
    ) {
        // Pending items to ingest
        val pendingFolders = ConcurrentLinkedQueue<FolderIngestItem>()
        val pendingFiles = ConcurrentLinkedQueue<FileIngestItem>()

        // Track folder statuses for inheritance
        val folderStatusCache = ConcurrentHashMap<String, AnalysisStatus>()

        // Build file visit options
        val visitOptions = if (followLinks) {
            setOf(FileVisitOption.FOLLOW_LINKS)
        } else {
            emptySet()
        }

        var lastHeartbeat = System.currentTimeMillis()

        Files.walkFileTree(startPath, visitOptions, maxDepth, object : SimpleFileVisitor<Path>() {

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val pathStr = dir.toAbsolutePath().toString()

                // Quick SKIP check during walk
                val skipCheck = patternMatcher.matchesSkipPatternOnly(
                    pathStr,
                    effectivePatterns.folderPatterns.skip
                )

                if (skipCheck.isSkip) {
                    log.debug("Skipping folder (SKIP pattern): {}", pathStr)

                    // Still record it with SKIP status
                    val metadata = FileSystemMetadata.fromPath(dir)
                    if (metadata != null) {
                        pendingFolders.add(createFolderItem(dir, AnalysisStatus.SKIP, metadata, allStartPaths))
                        stats.foldersProcessed.incrementAndGet()
                    }

                    folderStatusCache[pathStr] = AnalysisStatus.SKIP
                    return FileVisitResult.SKIP_SUBTREE
                }

                // Determine full analysis status
                val parentPath = dir.parent?.toAbsolutePath()?.toString()
                val parentStatus = if (parentPath != null) folderStatusCache[parentPath] else null

                val status = patternMatcher.determineFolderAnalysisStatus(
                    pathStr,
                    effectivePatterns.folderPatterns,
                    parentStatus,
                    effectivePatterns.folderPatternPriority
                )

                folderStatusCache[pathStr] = status

                val metadata = FileSystemMetadata.fromPath(dir)
                if (metadata != null) {
                    pendingFolders.add(createFolderItem(dir, status, metadata, allStartPaths))
                    stats.foldersProcessed.incrementAndGet()
                }

                // Flush if batch is full
                maybeFlush(pendingFolders, pendingFiles, host, config.crawlConfigId, sessionId, stats, batchController)

                // Heartbeat
                if (System.currentTimeMillis() - lastHeartbeat > heartbeatIntervalMs) {
                    sendHeartbeat(host, config.crawlConfigId, sessionId, stats, "Walking: $pathStr")
                    lastHeartbeat = System.currentTimeMillis()
                }

                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val pathStr = file.toAbsolutePath().toString()

                // Get parent folder status
                val parentPath = file.parent?.toAbsolutePath()?.toString()
                val parentStatus = if (parentPath != null) {
                    folderStatusCache[parentPath] ?: AnalysisStatus.LOCATE
                } else {
                    AnalysisStatus.LOCATE
                }

                // Determine file analysis status
                val status = patternMatcher.determineFileAnalysisStatus(
                    pathStr,
                    effectivePatterns.filePatterns,
                    parentStatus,
                    effectivePatterns.filePatternPriority
                )

                val metadata = FileSystemMetadata.fromPath(file)
                if (metadata != null) {
                    val fileItem = createFileItem(file, status, metadata, allStartPaths)
                    pendingFiles.add(fileItem)
                    stats.filesProcessed.incrementAndGet()
                    stats.bytesProcessed.addAndGet(metadata.size)
                }

                // Flush if batch is full
                maybeFlush(pendingFolders, pendingFiles, host, config.crawlConfigId, sessionId, stats, batchController)

                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                log.warn("Failed to visit file: {} - {}", file, exc.message)
                stats.errors.incrementAndGet()
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    log.warn("Error after visiting directory: {} - {}", dir, exc.message)
                    stats.errors.incrementAndGet()
                }
                return FileVisitResult.CONTINUE
            }
        })

        // Final flush
        flush(
            folders = pendingFolders,
            files = pendingFiles,
            host = host,
            crawlConfigId = config.crawlConfigId,
            sessionId = sessionId,
            stats = stats,
            batchController = batchController,
            drainCompletely = true
        )
    }

    private fun createFolderItem(
        path: Path,
        status: AnalysisStatus,
        metadata: FileSystemMetadata,
        startPaths: List<Path>
    ): FolderIngestItem {
        val depth = PathUtils.calculateCrawlDepth(path, startPaths)
        val permissions = getPermissions(path)

        return FolderIngestItem(
            path = path.toAbsolutePath().toString(),
            analysisStatus = status,
            label = metadata.name,
            crawlDepth = depth,
            size = metadata.size,
            fsLastModified = metadata.lastModifiedIso(),
            permissions = permissions
        )
    }

    private fun createFileItem(
        path: Path,
        status: AnalysisStatus,
        metadata: FileSystemMetadata,
        startPaths: List<Path>
    ): FileIngestItem {
        val depth = PathUtils.calculateCrawlDepth(path, startPaths)
        val permissions = getPermissions(path)

        // Extract text for INDEX+ files
        var bodyText: String? = null
        var bodySize: Long? = null
        var author: String? = null
        var title: String? = null
        var subject: String? = null
        var keywords: String? = null
        var comments: String? = null
        var creationDate: String? = null
        var modifiedDate: String? = null
        var language: String? = null
        var contentType: String? = null
        var pageCount: Int? = null

        if (status >= AnalysisStatus.INDEX && metadata.size > 0) {
            contentType = textExtractor.detectMimeType(path)

            when (val result = textExtractor.extractTextAndMetadata(path)) {
                is TextAndMetadataResult.Success -> {
                    bodyText = result.text
                    bodySize = result.text.length.toLong()
                    author = result.metadata.author
                    title = result.metadata.title
                    subject = result.metadata.subject
                    keywords = result.metadata.keywords
                    comments = result.metadata.comments
                    creationDate = result.metadata.creationDate
                    modifiedDate = result.metadata.modifiedDate
                    language = result.metadata.language
                    pageCount = result.metadata.pageCount
                }
                is TextAndMetadataResult.Failure -> {
                    log.debug("Text extraction failed for {}: {}", path, result.error)
                }
            }
        } else if (metadata.size > 0) {
            contentType = textExtractor.detectMimeType(path)
        }

        return FileIngestItem(
            path = path.toAbsolutePath().toString(),
            analysisStatus = status,
            label = metadata.name,
            crawlDepth = depth,
            size = metadata.size,
            fsLastModified = metadata.lastModifiedIso(),
            permissions = permissions,
            bodyText = bodyText,
            bodySize = bodySize,
            author = author,
            title = title,
            subject = subject,
            keywords = keywords,
            comments = comments,
            creationDate = creationDate,
            modifiedDate = modifiedDate,
            language = language,
            contentType = contentType,
            pageCount = pageCount
        )
    }

    private fun getPermissions(path: Path): String? {
        return try {
            val perms = Files.getPosixFilePermissions(path)
            PosixFilePermissions.toString(perms)
        } catch (e: Exception) {
            null
        }
    }

    private fun maybeFlush(
        folders: ConcurrentLinkedQueue<FolderIngestItem>,
        files: ConcurrentLinkedQueue<FileIngestItem>,
        host: String,
        crawlConfigId: Long,
        sessionId: Long,
        stats: CrawlStats,
        batchController: AdaptiveBatchController
    ) {
        if (folders.size + files.size >= batchController.currentItems()) {
            flush(folders, files, host, crawlConfigId, sessionId, stats, batchController, drainCompletely = false)
        }
    }

    private fun flush(
        folders: ConcurrentLinkedQueue<FolderIngestItem>,
        files: ConcurrentLinkedQueue<FileIngestItem>,
        host: String,
        crawlConfigId: Long,
        sessionId: Long,
        stats: CrawlStats,
        batchController: AdaptiveBatchController,
        drainCompletely: Boolean
    ) {
        while (folders.isNotEmpty() || files.isNotEmpty()) {
            val itemLimit = batchController.currentItems()
            val byteLimit = batchController.targetPayloadBytes

            val folderBatch = mutableListOf<FolderIngestItem>()
            val fileBatch = mutableListOf<FileIngestItem>()
            var estimatedBytes = 0L

            while ((folderBatch.size + fileBatch.size) < itemLimit) {
                val takeFolder = when {
                    folders.isEmpty() -> false
                    files.isEmpty() -> true
                    else -> folders.size >= files.size
                }
                if (takeFolder) {
                    val next = folders.peek() ?: continue
                    val bytes = estimateFolderPayloadBytes(next)
                    if ((folderBatch.size + fileBatch.size) > 0 && estimatedBytes + bytes > byteLimit) break
                    folders.poll()?.let {
                        folderBatch.add(it)
                        estimatedBytes += bytes
                    }
                } else {
                    val next = files.peek() ?: continue
                    val bytes = estimateFilePayloadBytes(next)
                    if ((folderBatch.size + fileBatch.size) > 0 && estimatedBytes + bytes > byteLimit) break
                    files.poll()?.let {
                        fileBatch.add(it)
                        estimatedBytes += bytes
                    }
                }
            }

            // Ensure forward progress when first item alone is larger than target payload.
            if (folderBatch.isEmpty() && fileBatch.isEmpty()) {
                folders.poll()?.let { folderBatch.add(it); estimatedBytes += estimateFolderPayloadBytes(it) }
                if (folderBatch.isEmpty()) {
                    files.poll()?.let { fileBatch.add(it); estimatedBytes += estimateFilePayloadBytes(it) }
                }
            }

            if (folderBatch.isEmpty() && fileBatch.isEmpty()) {
                return
            }

            val itemCount = folderBatch.size + fileBatch.size
            log.debug(
                "Flushing batch: {} folders, {} files, itemsTarget={}, bytes~{}",
                folderBatch.size,
                fileBatch.size,
                itemLimit,
                estimatedBytes
            )

            val started = System.currentTimeMillis()
            try {
                val response = client.ingest(
                    IngestRequest(
                        host = host,
                        crawlConfigId = crawlConfigId,
                        sessionId = sessionId,
                        folders = if (folderBatch.isNotEmpty()) folderBatch else null,
                        files = if (fileBatch.isNotEmpty()) fileBatch else null
                    )
                )
                val durationMs = System.currentTimeMillis() - started
                batchController.recordSuccess(itemCount, estimatedBytes, durationMs)
                log.debug(
                    "Ingested: {} folders, {} files in {}ms (next target={})",
                    response.foldersPersisted,
                    response.filesPersisted,
                    durationMs,
                    batchController.currentItems()
                )
            } catch (e: Exception) {
                log.error("Failed to ingest batch: {}", e.message)
                stats.errors.incrementAndGet()
                // Re-queue on failure to avoid silent data loss.
                folderBatch.forEach { folders.add(it) }
                fileBatch.forEach { files.add(it) }
                batchController.recordFailure()
                break
            }

            if (!drainCompletely) {
                return
            }
        }
    }

    private fun sendHeartbeat(
        host: String,
        crawlConfigId: Long,
        sessionId: Long,
        stats: CrawlStats,
        currentStep: String
    ) {
        try {
            client.heartbeat(
                SessionHeartbeatRequest(
                    host = host,
                    crawlConfigId = crawlConfigId,
                    sessionId = sessionId,
                    currentStep = currentStep,
                    processedIncrement = stats.filesProcessed.get() + stats.foldersProcessed.get()
                )
            )
        } catch (e: Exception) {
            log.warn("Heartbeat failed: {}", e.message)
        }
    }
}

private class AdaptiveBatchController(
    initialItems: Int,
    val minItems: Int,
    val maxItems: Int,
    val targetPayloadBytes: Long,
    private val adaptiveEnabled: Boolean
) {
    private var targetItems = initialItems.coerceIn(minItems, maxItems)
    private var latencyEmaMs: Double? = null
    private var failureStreak = 0

    fun currentItems(): Int = targetItems

    fun recordSuccess(sentItems: Int, payloadBytes: Long, durationMs: Long) {
        if (!adaptiveEnabled) return
        failureStreak = 0
        val current = latencyEmaMs
        latencyEmaMs = if (current == null) durationMs.toDouble() else (0.8 * current) + (0.2 * durationMs)

        val slow = durationMs >= 3_000 || payloadBytes >= targetPayloadBytes
        val fast = durationMs <= 800 &&
            payloadBytes < (targetPayloadBytes * 3 / 4) &&
            sentItems >= (targetItems * 9 / 10)

        if (slow) {
            targetItems = (targetItems * 7 / 10).coerceAtLeast(minItems)
        } else if (fast) {
            val growth = (targetItems / 5).coerceAtLeast(50)
            targetItems = (targetItems + growth).coerceAtMost(maxItems)
        }
    }

    fun recordFailure() {
        if (!adaptiveEnabled) return
        failureStreak++
        val shrinkFactor = if (failureStreak >= 3) 3 else 2
        targetItems = (targetItems / shrinkFactor).coerceAtLeast(minItems)
    }
}

private fun estimateFolderPayloadBytes(item: FolderIngestItem): Long {
    return 192L +
        (item.path.length * 2L) +
        ((item.label?.length ?: 0) * 2L) +
        ((item.description?.length ?: 0) * 2L) +
        ((item.owner?.length ?: 0) * 2L) +
        ((item.group?.length ?: 0) * 2L) +
        ((item.permissions?.length ?: 0) * 2L)
}

private fun estimateFilePayloadBytes(item: FileIngestItem): Long {
    return 256L +
        (item.path.length * 2L) +
        ((item.label?.length ?: 0) * 2L) +
        ((item.description?.length ?: 0) * 2L) +
        ((item.owner?.length ?: 0) * 2L) +
        ((item.group?.length ?: 0) * 2L) +
        ((item.permissions?.length ?: 0) * 2L) +
        ((item.bodyText?.length ?: 0) * 2L) +
        ((item.author?.length ?: 0) * 2L) +
        ((item.title?.length ?: 0) * 2L) +
        ((item.subject?.length ?: 0) * 2L) +
        ((item.keywords?.length ?: 0) * 2L) +
        ((item.comments?.length ?: 0) * 2L) +
        ((item.creationDate?.length ?: 0) * 2L) +
        ((item.modifiedDate?.length ?: 0) * 2L) +
        ((item.language?.length ?: 0) * 2L) +
        ((item.contentType?.length ?: 0) * 2L)
}

private class CrawlStats {
    val foldersProcessed = AtomicInteger(0)
    val filesProcessed = AtomicInteger(0)
    val bytesProcessed = AtomicLong(0)
    val errors = AtomicInteger(0)
}

data class CrawlResult(
    val sessionId: Long,
    val foldersProcessed: Int,
    val filesProcessed: Int,
    val bytesProcessed: Long,
    val errors: Int,
    val durationMs: Long,
    val success: Boolean,
    val errorMessage: String? = null
)
