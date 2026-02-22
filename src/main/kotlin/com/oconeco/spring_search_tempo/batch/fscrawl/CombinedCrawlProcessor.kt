package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.config.EffectivePatterns
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
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
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import kotlin.io.path.name

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
    private val forceFullRecrawl: Boolean = false
) : ItemProcessor<CombinedCrawlItem, CombinedCrawlResult> {

    companion object {
        private val log = LoggerFactory.getLogger(CombinedCrawlProcessor::class.java)
        private const val MAX_TEXT_EXTRACT_SIZE = 100 * 1024 * 1024 // 10MB limit  10,444,800
    }

    // Cache for parent folder analysis status (supports hierarchical matching)
    private val parentStatusCache = mutableMapOf<String, AnalysisStatus>()

    // Batch cache for file lookups within a directory
    // TODO: Consider using Spring Cache abstraction or Caffeine for more sophisticated caching
    private val fileCache = mutableMapOf<String, com.oconeco.spring_search_tempo.base.domain.FSFile?>()
    private val folderCache = mutableMapOf<String, com.oconeco.spring_search_tempo.base.domain.FSFolder?>()

    override fun process(item: CombinedCrawlItem): CombinedCrawlResult? {
        log.debug(
            "Processing combined item: directory={}, files={}",
            item.directory, item.files.size
        )

        // Process the folder first
        val folderDto = processFolder(item.directory, item.files.size) ?: run {
            // Folder is unchanged - skip entire directory including files
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

        // Check if folder exists in DB (use cache)
        val existingFolder = folderCache.getOrPut(uri) {
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
            parentStatus = parentStatus
        )

        // Cache this folder's status for its children
        parentStatusCache[uri] = analysisStatus

        // If folder is SKIP, persist metadata but don't process children
        // Return the DTO so metadata is saved, but children won't be crawled
        if (analysisStatus == AnalysisStatus.SKIP) {
            log.info("\t\tFolder marked as SKIP by patterns, persisting metadata only: {}", uri)
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

        // Batch lookup: get all existing files in this directory
        // TODO: Optimize with single query using IN clause: findByUriIn(uris)
        val fileUris = files.map { it.toString() }
        fileUris.forEach { uri ->
            if (!fileCache.containsKey(uri)) {
                fileRepository.findByUri(uri)?.let { fileCache[uri] = it }
            }
        }

        // Process each file
        val fileDtos = mutableListOf<FSFileDTO>()
        for (file in files) {
            val dto = processFile(file, parentFolderStatus)
            if (dto != null) {
                log.info("\t\t++++ File {} will be persisted", file)
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
        log.info("\t\tProcessing file: {}", uri)

        // Extract lightweight filesystem metadata
        val fsMetadata = FileSystemMetadata.fromPath(file)
        if (fsMetadata == null) {
            log.warn("Could not extract metadata for file, skipping: {}", uri)
            return null
        }

        // Check if file exists in DB (use cache)
        val existingFile = fileCache[uri]

        // Incremental crawl: check if file is unchanged (skip check when forceFullRecrawl=true)
        if (existingFile == null) {
            log.info("File does not exist in DB, will process: {}", uri)
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
            parentFolderStatus = parentFolderStatus ?: AnalysisStatus.LOCATE
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
            log.warn("File not readable (permission denied), skipping extraction: {}", file)
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

                log.info("\t\tExtracted text and metadata from: {} ({} chars)", dto.uri, result.text.length)        // todo -- change to debug
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
        parentStatusCache[parentUri]?.let { return it }

        // Check database (via folder cache)
        val parentFolder = folderCache.getOrPut(parentUri) {
            folderRepository.findByUri(parentUri)
        }

        return parentFolder?.analysisStatus?.also {
            parentStatusCache[parentUri] = it
        }
    }

    /**
     * Clear all caches. Useful for testing or between batch job runs.
     */
    fun clearCaches() {
        parentStatusCache.clear()
        fileCache.clear()
        folderCache.clear()
        log.debug("Cleared all processor caches")
    }
}
