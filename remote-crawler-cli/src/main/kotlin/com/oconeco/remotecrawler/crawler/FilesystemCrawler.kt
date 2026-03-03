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

        val effectivePatterns = EffectivePatterns(
            folderPatterns = config.folderPatterns,
            filePatterns = config.filePatterns
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
                    stats = stats
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
        stats: CrawlStats
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
                    parentStatus
                )

                folderStatusCache[pathStr] = status

                val metadata = FileSystemMetadata.fromPath(dir)
                if (metadata != null) {
                    pendingFolders.add(createFolderItem(dir, status, metadata, allStartPaths))
                    stats.foldersProcessed.incrementAndGet()
                }

                // Flush if batch is full
                maybeFlush(pendingFolders, pendingFiles, host, config.crawlConfigId, sessionId, stats)

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
                    parentStatus
                )

                val metadata = FileSystemMetadata.fromPath(file)
                if (metadata != null) {
                    val fileItem = createFileItem(file, status, metadata, allStartPaths)
                    pendingFiles.add(fileItem)
                    stats.filesProcessed.incrementAndGet()
                    stats.bytesProcessed.addAndGet(metadata.size)
                }

                // Flush if batch is full
                maybeFlush(pendingFolders, pendingFiles, host, config.crawlConfigId, sessionId, stats)

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
        flush(pendingFolders, pendingFiles, host, config.crawlConfigId, sessionId, stats)
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
        stats: CrawlStats
    ) {
        if (folders.size + files.size >= batchSize) {
            flush(folders, files, host, crawlConfigId, sessionId, stats)
        }
    }

    private fun flush(
        folders: ConcurrentLinkedQueue<FolderIngestItem>,
        files: ConcurrentLinkedQueue<FileIngestItem>,
        host: String,
        crawlConfigId: Long,
        sessionId: Long,
        stats: CrawlStats
    ) {
        if (folders.isEmpty() && files.isEmpty()) {
            return
        }

        val folderBatch = mutableListOf<FolderIngestItem>()
        val fileBatch = mutableListOf<FileIngestItem>()

        // Drain queues
        while (folderBatch.size < batchSize) {
            val folder = folders.poll() ?: break
            folderBatch.add(folder)
        }

        while (fileBatch.size < batchSize) {
            val file = files.poll() ?: break
            fileBatch.add(file)
        }

        if (folderBatch.isEmpty() && fileBatch.isEmpty()) {
            return
        }

        log.debug("Flushing batch: {} folders, {} files", folderBatch.size, fileBatch.size)

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
            log.debug("Ingested: {} folders, {} files", response.foldersPersisted, response.filesPersisted)
        } catch (e: Exception) {
            log.error("Failed to ingest batch: {}", e.message)
            stats.errors.incrementAndGet()
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
