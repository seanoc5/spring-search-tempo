package com.oconeco.spring_search_tempo.batch.fscrawl

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import com.oconeco.spring_search_tempo.base.service.TextAndMetadataResult
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Combined processor that handles both folders and their files in a single pass.
 * This replaces the separate FolderProcessor + FileProcessor approach with a more
 * efficient strategy that:
 * 1. Processes directory and determines if it should be indexed
 * 2. Batches DB lookups for existing folders/files in the directory
 * 3. Uses lightweight metadata comparison to skip unchanged items
 * 4. Processes all files in the directory while it's "hot" in cache
 *
 * Supports multiple start paths using longest prefix matching for depth calculation.
 *
 * @param startPaths List of root paths for calculating crawl depth
 * @param effectivePatterns Merged pattern set for this crawl
 * @param folderRepository Repository for folder lookups/caching
 * @param fileRepository Repository for file lookups/caching
 * @param folderMapper Mapper for folder DTO conversion
 * @param fileMapper Mapper for file DTO conversion
 * @param patternMatchingService Service for determining analysis status
 * @param textExtractionService Service for text extraction from files
 * @param forceFullRecrawl When true, skip timestamp checks and re-process all items
 * @param meterRegistry Optional Micrometer registry. When provided, the four
 *   Caffeine caches expose hit/miss/size gauges under `cache.*` with `cache=`
 *   tags. Pass null in unit tests that don't care about observability.
 * @param maxCacheSize Maximum entries per cache. Default 50_000 keeps memory
 *   bounded on full-system crawls while still absorbing the locality typical
 *   of directory-by-directory walks. Tests can dial this down to force
 *   eviction without driving millions of paths through the processor.
 */
class CombinedCrawlProcessor(
    private val startPaths: List<Path>,
    private val effectivePatterns: EffectivePatterns,
    private val folderRepository: FSFolderRepository,
    private val fileRepository: FSFileRepository,
    private val folderMapper: FSFolderMapper,
    private val fileMapper: FSFileMapper,
    private val patternMatchingService: PatternMatchingService,
    private val textExtractionService: TextExtractionService,
    private val forceFullRecrawl: Boolean = false,
    meterRegistry: MeterRegistry? = null,
    private val maxCacheSize: Long = DEFAULT_MAX_CACHE_SIZE
) : ItemProcessor<CombinedCrawlItem, CombinedCrawlResult> {

    companion object {
        private val log = LoggerFactory.getLogger(CombinedCrawlProcessor::class.java)
        private const val MAX_TEXT_EXTRACT_SIZE = 100 * 1024 * 1024 // 10MB limit  10,444,800

        /** Default per-cache entry cap. ~50k is comfortably above one directory's
         *  worth of files for normal trees while keeping worst-case heap small. */
        const val DEFAULT_MAX_CACHE_SIZE: Long = 50_000

        /** Idle-eviction window. Five minutes is long enough to keep a directory's
         *  lookups hot through its continuation batches and the next sibling
         *  directory, short enough that abandoned regions don't pin memory. */
        private val EXPIRE_AFTER_ACCESS: java.time.Duration = java.time.Duration.ofMinutes(5)
    }

    private fun <K : Any, V : Any> buildCache(): Cache<K, V> =
        Caffeine.newBuilder()
            .maximumSize(maxCacheSize)
            .expireAfterAccess(EXPIRE_AFTER_ACCESS.toMinutes(), TimeUnit.MINUTES)
            .recordStats()
            .build()

    // Cache for parent folder analysis status (supports hierarchical matching).
    // Grows with the number of unique directories visited, so bound it.
    internal val parentStatusCache: Cache<String, AnalysisStatus> = buildCache()

    // Cache for folders that were skipped (unchanged) - continuation batches should also skip.
    // Caffeine has no Set primitive, so we use a Cache<String, Boolean> as a bounded set.
    internal val skippedFolderCache: Cache<String, Boolean> = buildCache()

    // Cache for file lookups within (and across) recently visited directories.
    // Caffeine rejects null values; we put only on DB hits, so misses naturally
    // fall through to the next batched IN-clause query — same semantics as the
    // ConcurrentHashMap version this replaces.
    internal val fileCache: Cache<String, FSFile> = buildCache()

    // Folder cache. Uses Cache.get(key, loader) which treats a null loader
    // return as "do not cache" — preserves the prior null-suppression semantics
    // without needing a sentinel value.
    internal val folderCache: Cache<String, FSFolder> = buildCache()

    init {
        meterRegistry?.let { registry ->
            // Bind each cache to Micrometer so cache.size / cache.gets / cache.puts
            // surface at /actuator/metrics. Each processor instance lives for one
            // job; we tag with an instance discriminator so re-binding across runs
            // doesn't collide on (name, tags) and silently drop the new meters.
            val instanceTag = Tag.of("instance", java.lang.Long.toString(System.identityHashCode(this).toLong() and 0xFFFFFFFFL, 36))
            CaffeineCacheMetrics.monitor(registry, fileCache, "fileCache", listOf(instanceTag))
            CaffeineCacheMetrics.monitor(registry, folderCache, "folderCache", listOf(instanceTag))
            CaffeineCacheMetrics.monitor(registry, parentStatusCache, "parentStatusCache", listOf(instanceTag))
            CaffeineCacheMetrics.monitor(registry, skippedFolderCache, "skippedFolderCache", listOf(instanceTag))
        }
    }

    override fun process(item: CombinedCrawlItem): CombinedCrawlResult? {
        log.debug(
            "Processing combined item: directory={}, files={}, isContinuation={}",
            item.directory, item.files.size, item.isContinuation
        )

        // Handle continuation batches (folder already processed in first batch)
        if (item.isContinuation) {
            return processContinuationBatch(item)
        }

        // First batch: process the folder
        val folderDto = processFolder(item.directory, item.totalFileCount) ?: run {
            // Folder is unchanged - skip entire directory including files
            // Mark as skipped so continuation batches also skip
            skippedFolderCache.put(item.directory.toString(), true)
            log.debug("\t\tSkipping unchanged directory and all {} files: {}", item.files.size, item.directory)
            return null
        }

        // If folder is SKIP, persist folder metadata but don't process any children
        if (folderDto.analysisStatus == AnalysisStatus.SKIP) {
            log.info(".... Folder marked as SKIP, persisting folder metadata only (no children processed): {}", folderDto.uri)
            return CombinedCrawlResult(
                folder = folderDto,
                files = emptyList()  // No files processed - SKIP stops crawling here
            )
        }

        // If folder is being processed normally, process its files
        // Batch lookup existing files in this directory for efficient comparison
        val fileDtos = processFiles(item.files, folderDto.analysisStatus)

        return CombinedCrawlResult(
            folder = folderDto,
            files = fileDtos
        )
    }

    /**
     * Process a continuation batch (2nd, 3rd, etc. batch of a large directory).
     * Folder was already processed in first batch - just process files using cached status.
     */
    private fun processContinuationBatch(item: CombinedCrawlItem): CombinedCrawlResult? {
        val dirUri = item.directory.toString()

        // Check if first batch decided to skip this folder (unchanged)
        if (skippedFolderCache.getIfPresent(dirUri) == true) {
            log.debug("Continuation batch for unchanged folder, skipping {} files: {}", item.files.size, dirUri)
            return null
        }

        // Get parent folder's analysis status from cache (set during first batch)
        val parentStatus = parentStatusCache.getIfPresent(dirUri) ?: run {
            // Fallback: check database if not in cache (shouldn't happen normally)
            val existingFolder = folderCache.get(dirUri) { folderRepository.findByUri(dirUri) }
            existingFolder?.analysisStatus ?: run {
                log.warn("Continuation batch but folder not found in cache or DB: {}", dirUri)
                return null
            }
        }

        // SKIP folders shouldn't have continuation batches (reader splits before SKIP check)
        // but handle it defensively
        if (parentStatus == AnalysisStatus.SKIP) {
            log.debug("Continuation batch for SKIP folder, skipping files: {}", dirUri)
            return null
        }

        log.debug("Processing continuation batch: {} files for {} (parentStatus={})",
            item.files.size, dirUri, parentStatus)

        val fileDtos = processFiles(item.files, parentStatus)

        return CombinedCrawlResult(
            folder = null,  // Folder already persisted in first batch
            files = fileDtos
        )
    }

    /**
     * Process a single folder with pattern matching and incremental crawl support.
     * @param directory The directory path to process
     * @param fileCount The number of immediate child files in this directory
     */
    private fun processFolder(directory: Path, fileCount: Int): FSFolderDTO? {
        val uri = directory.toString()
        log.info("Processing folder: {}", uri)

        // Extract lightweight filesystem metadata
        val fsMetadata = FileSystemMetadata.fromPath(directory)
        if (fsMetadata == null) {
            log.warn("Could not extract metadata for folder, skipping: {}", uri)
            return null
        }

        // Check if folder exists in DB (use cache; null result is not cached)
        val existingFolder = folderCache.get(uri) {
            val existing = folderRepository.findByUri(uri)
            log.debug("\t\tFound existing folder: {}", existing)
            existing
        }

        // Incremental crawl: check if folder is unchanged (skip check when forceFullRecrawl=true)
        if (existingFolder != null) {
            val isUnchanged = fsMetadata.isUnchanged(
                dbLastModified = existingFolder.fsLastModified,
                dbSize = existingFolder.size
            )

            if (!forceFullRecrawl && isUnchanged && existingFolder.status == Status.CURRENT) {
                log.info("\t\t.... Folder unchanged, skipping: {}", uri)
                return null
            }

            if (forceFullRecrawl) {
                log.info("\t\t++++ [{}] Folder exists, forcing recrawl (forceFullRecrawl=true)", uri)
            } else {
                log.info("\t\t++++ [{}] Folder exists, will update, (unchanged={}, status={})", uri, isUnchanged, existingFolder.status)
            }
        }

        // Determine analysis status using hierarchical pattern matching
        val parentStatus = getParentAnalysisStatus(directory)
        val analysisStatus = patternMatchingService.determineFolderAnalysisStatus(
            path = uri,
            patterns = effectivePatterns.folderPatterns,
            parentStatus = parentStatus,
            priority = effectivePatterns.folderPatternPriority
        )

        // Cache this folder's status for its children
        parentStatusCache.put(uri, analysisStatus)

        // If folder is SKIP, persist metadata but don't process children
        // Return the DTO so metadata is saved, but children won't be crawled
        if (analysisStatus == AnalysisStatus.SKIP) {
            log.debug("\t\tFolder marked as SKIP by patterns, persisting metadata only: {}", uri)
            // Continue to create DTO below - it will be persisted with SKIP status
        }

        // Create or update DTO
        val dto = if (existingFolder != null) {
            folderMapper.updateFSFolderDTO(existingFolder, FSFolderDTO()).also {
                it.status = Status.CURRENT
            }
        } else {
            FSFolderDTO().also {
                it.status = Status.NEW
                it.version = 0L
            }
        }

        // todo -- review if this is redundant...?
        // Set folder properties
        dto.type = "FOLDER"
        dto.uri = uri
        dto.label = fsMetadata.name
        dto.crawlDepth = calculateCrawlDepth(directory)
        dto.fsLastModified = fsMetadata.lastModified
        dto.size = fileCount.toLong()
        dto.analysisStatus = analysisStatus

        // Try to read POSIX attributes (owner, group, permissions)
        readPosixAttributes(directory, dto)

        log.info("\t\tProcessed folder: uri={}, status={}, analysisStatus={}", dto.uri, dto.status, dto.analysisStatus
        )

        return dto
    }

    /**
     * Process multiple files from a directory with batch caching and metadata comparison.
     */
    private fun processFiles(files: List<Path>, parentFolderStatus: AnalysisStatus?): List<FSFileDTO> {
        if (files.isEmpty()) {
            return emptyList()
        }

        log.trace("Processing {} files with parent folder status: {}", files.size, parentFolderStatus)

        // Batch lookup: a single IN-clause query for every URI not already cached.
        // Caffeine (like the prior ConcurrentHashMap) rejects null values, so we
        // only populate hits — misses stay absent and read as null in processFile,
        // matching the old semantics.
        val cacheView = fileCache.asMap()
        val urisToFetch = files.map { it.toString() }.filter { !cacheView.containsKey(it) }
        if (urisToFetch.isNotEmpty()) {
            fileRepository.findByUriIn(urisToFetch).forEach { file ->
                file.uri?.let { fileCache.put(it, file) }
            }
        }

        // Process each file
        val fileDtos = mutableListOf<FSFileDTO>()
        for (file in files) {
            val dto = processFile(file, parentFolderStatus)
            if (dto != null) {
                log.debug("\t\t++++ File {} will be persisted", file)
                fileDtos.add(dto)
            } else {
                log.info("\t\t.... File {} was skipped (maybe already current or skipped by pattern) ", file)
            }
        }

        log.info("Processed {} files, {} will be persisted", files.size, fileDtos.size)
        return fileDtos
    }

    /**
     * Process a single file with pattern matching, metadata comparison, and text extraction.
     */
    private fun processFile(file: Path, parentFolderStatus: AnalysisStatus?): FSFileDTO? {
        val uri = file.toString()
        log.debug("\t\tProcessing file: {}", uri)

        // Extract lightweight filesystem metadata
        val fsMetadata = FileSystemMetadata.fromPath(file)
        if (fsMetadata == null) {
            log.warn("Could not extract metadata for file, skipping: {}", uri)
            return null
        }

        // Check if file exists in DB (use cache)
        val existingFile = fileCache.getIfPresent(uri)

        // Incremental crawl: check if file is unchanged (skip check when forceFullRecrawl=true)
        if (existingFile == null) {
            log.debug("\t\t++++ File does not exist in DB, will process: {}", uri)
        } else {
            val isUnchanged = fsMetadata.isUnchanged(
                dbLastModified = existingFile.fsLastModified,
                dbSize = existingFile.size
            )

            if (!forceFullRecrawl && isUnchanged) {
                log.debug("\t\tFile unchanged, skipping: {}", uri)
                return null
            }

            if (forceFullRecrawl) {
                log.info("\t\tFile exists, forcing recrawl (forceFullRecrawl=true): {}", uri)
            } else {
                log.info("\t\tFile modified, will update: {} (fs_modified={}, db_modified={})",
                    uri, fsMetadata.lastModified, existingFile.fsLastModified)
            }
        }

        // Determine analysis status using file patterns and parent folder status
        val analysisStatus = patternMatchingService.determineFileAnalysisStatus(
            path = uri,
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = parentFolderStatus ?: AnalysisStatus.LOCATE,
            priority = effectivePatterns.filePatternPriority
        )

        // Create or update DTO
        val dto = FSFileDTO()
        dto.id = existingFile?.id
        dto.status = if (existingFile != null) Status.CURRENT else Status.NEW
        dto.uri = uri
        dto.label = fsMetadata.name
        dto.type = "FILE"
        dto.crawlDepth = calculateCrawlDepth(file)
        dto.fsLastModified = fsMetadata.lastModified
        dto.size = fsMetadata.size
        dto.analysisStatus = analysisStatus

        // Set version for optimistic locking
        if (dto.version == null) {
            log.debug("File [{}] version not set, setting to 0", uri)
            dto.version = 0L
        } else {
            log.info("File version already set: {}", dto.version)       // todo -- move to debug
        }

        // Extract text and metadata based on analysis status
        when (analysisStatus) {
            AnalysisStatus.SKIP -> {
                log.debug("File marked as SKIP, persisting metadata only (no text extraction): {}", uri)
                // Metadata already set above - no text extraction
            }
            AnalysisStatus.LOCATE -> {
                log.debug("\t\tFile marked as LOCATE, metadata only: {}", uri)
                // Metadata already set above - no text extraction
            }
            AnalysisStatus.INDEX, AnalysisStatus.ANALYZE -> {
                log.debug("\t\tExtracting text and metadata for file ({}): {}", analysisStatus, uri)
                extractTextAndMetadata(file, dto)
            }
            AnalysisStatus.SEMANTIC -> {
                log.debug("\t\tFile marked as SEMANTIC (future), treating as INDEX for now: {}", uri)
                extractTextAndMetadata(file, dto)
            }
        }

        // Read POSIX attributes if available
        readPosixAttributes(file, dto)

        log.debug("\t\tProcessed file: uri={}, status={}, analysisStatus={}, hasText={}",
            dto.uri, dto.status, dto.analysisStatus, dto.bodyText != null
        )

        return dto
    }

    /**
     * Extract text and metadata from a file using Tika.
     * Includes pre-read check for file accessibility.
     */
    private fun extractTextAndMetadata(file: Path, dto: FSFileDTO) {
        // Pre-read check: verify file is readable before attempting extraction
        if (!Files.isReadable(file)) {
            log.info("File not readable (permission denied), skipping extraction: {}", file)
            dto.bodyText = "[Access denied: file not readable]"
            dto.accessDenied = true
            return
        }

        if (dto.bodySize != null && dto.bodySize!! > MAX_TEXT_EXTRACT_SIZE) {
            log.warn("File too large for text extraction ({}MB), skipping: {}",
                dto.bodySize!! / 1024 / 1024, dto.uri
            )
            dto.bodyText = "[File too large for extraction]"
            // File too large is not an error or access denied - just a limitation
            return
        }

        when (val result = textExtractionService.extractTextAndMetadata(file, MAX_TEXT_EXTRACT_SIZE.toLong())) {
            is TextAndMetadataResult.Success -> {
                dto.bodyText = result.text
                dto.bodySize = result.text.length.toLong()
                dto.author = result.metadata.author
                dto.title = result.metadata.title
                dto.subject = result.metadata.subject
                dto.contentType = result.metadata.contentType
                dto.modifiedDate = result.metadata.modifiedDate
                dto.pageCount = result.metadata.pageCount

                log.debug("\t\tExtracted text and metadata from: {} ({} chars)", dto.uri, result.text.length)        // todo -- change to debug
            }

            is TextAndMetadataResult.Failure -> {
                dto.bodyText = "[Extraction failed: ${result.error}]"
                // Distinguish access denied from other errors
                if (result.error.contains("Access denied") || result.error.contains("File not found")) {
                    dto.accessDenied = true
                } else {
                    dto.extractionError = true
                }
                log.warn("Text extraction failed for: {} - {}", dto.uri, result.error)
            }
        }
    }

    /**
     * Read POSIX file attributes (owner, group, permissions) if available.
     */
    private fun readPosixAttributes(path: Path, dto: Any) {
        try {
            val attrs = Files.readAttributes(path, PosixFileAttributes::class.java)
            when (dto) {
                is FSFolderDTO -> {
                    dto.owner = attrs.owner()?.name
                    dto.group = attrs.group()?.name
                    dto.permissions = formatPermissions(attrs)
                }

                is FSFileDTO -> {
                    dto.owner = attrs.owner()?.name
                    dto.group = attrs.group()?.name
                    dto.permissions = formatPermissions(attrs)
                }
            }
        } catch (e: UnsupportedOperationException) {
            // Not a POSIX filesystem (e.g., Windows)
            log.trace("POSIX attributes not available for: {}", path)
        } catch (e: Exception) {
            log.debug("Failed to read POSIX attributes for: {}", path, e)
        }
    }

    /**
     * Format POSIX permissions as a string (e.g., "rwxr-xr-x").
     */
    private fun formatPermissions(attrs: PosixFileAttributes): String {
        return attrs.permissions()?.joinToString("") {
            when (it.toString()) {
                "OWNER_READ" -> "r"
                "OWNER_WRITE" -> "w"
                "OWNER_EXECUTE" -> "x"
                "GROUP_READ" -> "r"
                "GROUP_WRITE" -> "w"
                "GROUP_EXECUTE" -> "x"
                "OTHERS_READ" -> "r"
                "OTHERS_WRITE" -> "w"
                "OTHERS_EXECUTE" -> "x"
                else -> ""
            }
        } ?: ""
    }

    /**
     * Calculate crawl depth relative to matching start path.
     * Uses longest prefix match to find the appropriate start path.
     */
    private fun calculateCrawlDepth(path: Path): Int {
        return PathUtils.calculateCrawlDepth(path, startPaths)
    }

    /**
     * Get parent folder's analysis status from cache or database.
     */
    private fun getParentAnalysisStatus(path: Path): AnalysisStatus? {
        val parent = path.parent ?: return null
        val parentUri = parent.toString()

        // Check cache first
        parentStatusCache.getIfPresent(parentUri)?.let { return it }

        // Check database (via folder cache; null is not cached)
        val parentFolder = folderCache.get(parentUri) { folderRepository.findByUri(parentUri) }

        return parentFolder?.analysisStatus?.also {
            parentStatusCache.put(parentUri, it)
        }
    }

    /**
     * Clear all caches. Useful for testing or between batch job runs.
     */
    fun clearCaches() {
        parentStatusCache.invalidateAll()
        skippedFolderCache.invalidateAll()
        fileCache.invalidateAll()
        folderCache.invalidateAll()
        log.debug("Cleared all processor caches")
    }
}
