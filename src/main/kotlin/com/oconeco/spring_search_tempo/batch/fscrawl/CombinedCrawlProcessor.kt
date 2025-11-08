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
 * @param startPath Root path for calculating crawl depth
 * @param effectivePatterns Merged pattern set for this crawl
 * @param folderRepository Repository for folder lookups/caching
 * @param fileRepository Repository for file lookups/caching
 * @param folderMapper Mapper for folder DTO conversion
 * @param fileMapper Mapper for file DTO conversion
 * @param patternMatchingService Service for determining analysis status
 * @param textExtractionService Service for text extraction from files
 */
class CombinedCrawlProcessor(
    private val startPath: Path,
    private val effectivePatterns: EffectivePatterns,
    private val folderRepository: FSFolderRepository,
    private val fileRepository: FSFileRepository,
    private val folderMapper: FSFolderMapper,
    private val fileMapper: FSFileMapper,
    private val patternMatchingService: PatternMatchingService,
    private val textExtractionService: TextExtractionService
) : ItemProcessor<CombinedCrawlItem, CombinedCrawlResult> {

    companion object {
        private val log = LoggerFactory.getLogger(CombinedCrawlProcessor::class.java)
        private const val MAX_TEXT_EXTRACT_SIZE = 10 * 1024 * 1024 // 10MB limit
    }

    // Cache for parent folder analysis status (supports hierarchical matching)
    private val parentStatusCache = mutableMapOf<String, AnalysisStatus>()

    // Batch cache for file lookups within a directory
    // TODO: Consider using Spring Cache abstraction or Caffeine for more sophisticated caching
    private val fileCache = mutableMapOf<String, com.oconeco.spring_search_tempo.base.domain.FSFile?>()
    private val folderCache = mutableMapOf<String, com.oconeco.spring_search_tempo.base.domain.FSFolder?>()

    override fun process(item: CombinedCrawlItem): CombinedCrawlResult? {
        log.debug("Processing combined item: directory={}, files={}",
            item.directory, item.files.size)

        // Process the folder first
        val folderDto = processFolder(item.directory) ?: run {
            // Folder is IGNORE or unchanged - skip entire directory including files
            log.debug("Skipping directory and all {} files: {}", item.files.size, item.directory)
            return null
        }

        // If folder is being processed, process its files
        // Batch lookup existing files in this directory for efficient comparison
        val fileDtos = processFiles(item.files, folderDto.analysisStatus)

        return CombinedCrawlResult(
            folder = folderDto,
            files = fileDtos
        )
    }

    /**
     * Process a single folder with pattern matching and incremental crawl support.
     */
    private fun processFolder(directory: Path): FSFolderDTO? {
        val uri = directory.toString()
        log.trace("Processing folder: {}", uri)

        // Extract lightweight filesystem metadata
        val fsMetadata = FileSystemMetadata.fromPath(directory)
        if (fsMetadata == null) {
            log.warn("Could not extract metadata for folder, skipping: {}", uri)
            return null
        }

        // Check if folder exists in DB (use cache)
        val existingFolder = folderCache.getOrPut(uri) {
            folderRepository.findByUri(uri)
        }

        // Incremental crawl: check if folder is unchanged
        if (existingFolder != null) {
            val isUnchanged = fsMetadata.isUnchanged(
                dbLastModified = existingFolder.fsLastModified,
                dbSize = null // Folders don't have meaningful size
            )

            if (isUnchanged && existingFolder.status == Status.CURRENT) {
                log.debug("Folder unchanged, skipping: {}", uri)
                return null
            }

            log.debug("Folder exists, will update: {} (unchanged={}, status={})",
                uri, isUnchanged, existingFolder.status)
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

        // If folder is IGNORE, skip it entirely
        if (analysisStatus == AnalysisStatus.IGNORE) {
            log.debug("Folder marked as IGNORE by patterns, skipping: {}", uri)
            return null
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

        // Set folder properties
        dto.type = "FOLDER"
        dto.uri = uri
        dto.label = fsMetadata.name
        dto.crawlDepth = calculateCrawlDepth(directory)
        dto.fsLastModified = fsMetadata.lastModified
        dto.analysisStatus = analysisStatus

        // Try to read POSIX attributes (owner, group, permissions)
        readPosixAttributes(directory, dto)

        log.trace("Processed folder: uri={}, status={}, analysisStatus={}",
            dto.uri, dto.status, dto.analysisStatus)

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
                fileDtos.add(dto)
            }
        }

        log.trace("Processed {} files, {} will be persisted", files.size, fileDtos.size)
        return fileDtos
    }

    /**
     * Process a single file with pattern matching, metadata comparison, and text extraction.
     */
    private fun processFile(file: Path, parentFolderStatus: AnalysisStatus?): FSFileDTO? {
        val uri = file.toString()
        log.trace("Processing file: {}", uri)

        // Extract lightweight filesystem metadata
        val fsMetadata = FileSystemMetadata.fromPath(file)
        if (fsMetadata == null) {
            log.warn("Could not extract metadata for file, skipping: {}", uri)
            return null
        }

        // Check if file exists in DB (use cache)
        val existingFile = fileCache[uri]

        // Incremental crawl: check if file is unchanged
        if (existingFile != null) {
            val isUnchanged = fsMetadata.isUnchanged(
                dbLastModified = existingFile.fsLastModified,
                dbSize = existingFile.bodySize
            )

            if (isUnchanged) {
                log.debug("File unchanged, skipping: {}", uri)
                return null
            }

            log.debug("File modified, will update: {} (fs_modified={}, db_modified={})",
                uri, fsMetadata.lastModified, existingFile.fsLastModified)
        }

        // Determine analysis status using file patterns and parent folder status
        val analysisStatus = patternMatchingService.determineFileAnalysisStatus(
            path = uri,
            filePatterns = effectivePatterns.filePatterns,
            parentFolderStatus = parentFolderStatus ?: AnalysisStatus.LOCATE
        )

        // If file is IGNORE, skip it
        if (analysisStatus == AnalysisStatus.IGNORE) {
            log.trace("File marked as IGNORE by patterns, skipping: {}", uri)
            return null
        }

        // Create or update DTO
        val dto = FSFileDTO()
        dto.id = existingFile?.id
        dto.status = if (existingFile != null) Status.CURRENT else Status.NEW
        dto.uri = uri
        dto.label = fsMetadata.name
        dto.type = "FILE"
        dto.crawlDepth = calculateCrawlDepth(file)
        dto.fsLastModified = fsMetadata.lastModified
        dto.bodySize = fsMetadata.size
        dto.analysisStatus = analysisStatus

        // Set version for optimistic locking
        if (dto.version == null) {
            dto.version = 0L
        }

        // Extract text and metadata if status is INDEX or ANALYZE
        if (analysisStatus == AnalysisStatus.INDEX || analysisStatus == AnalysisStatus.ANALYZE) {
            extractTextAndMetadata(file, dto)
        }

        // Read POSIX attributes if available
        readPosixAttributes(file, dto)

        log.trace("Processed file: uri={}, status={}, analysisStatus={}, hasText={}",
            dto.uri, dto.status, dto.analysisStatus, dto.bodyText != null)

        return dto
    }

    /**
     * Extract text and metadata from a file using Tika.
     */
    private fun extractTextAndMetadata(file: Path, dto: FSFileDTO) {
        if (dto.bodySize != null && dto.bodySize!! > MAX_TEXT_EXTRACT_SIZE) {
            log.warn("File too large for text extraction ({}MB), skipping: {}",
                dto.bodySize!! / 1024 / 1024, dto.uri)
            dto.bodyText = "[File too large for extraction]"
            return
        }

        when (val result = textExtractionService.extractTextAndMetadata(file, MAX_TEXT_EXTRACT_SIZE.toLong())) {
            is TextAndMetadataResult.Success -> {
                dto.bodyText = result.text
                dto.author = result.metadata.author
                dto.title = result.metadata.title
                dto.subject = result.metadata.subject
                dto.contentType = result.metadata.contentType
                dto.modifiedDate = result.metadata.modifiedDate
                dto.pageCount = result.metadata.pageCount

                log.trace("Extracted text and metadata from: {} ({} chars)",
                    dto.uri, result.text.length)
            }
            is TextAndMetadataResult.Failure -> {
                dto.bodyText = "[Extraction failed: ${result.error}]"
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
     * Calculate crawl depth relative to start path.
     */
    private fun calculateCrawlDepth(path: Path): Int {
        return try {
            val relativePath = startPath.relativize(path)
            relativePath.nameCount
        } catch (e: Exception) {
            log.warn("Failed to calculate crawl depth for: {}", path, e)
            0
        }
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
