package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * ItemProcessor for files that:
 * 1. Checks if file already exists (incremental crawl support)
 * 2. Applies file pattern matching to determine AnalysisStatus
 * 3. Extracts text content based on AnalysisStatus
 * 4. Creates or updates FSFileDTO
 *
 * @param startPath Root path of the crawl (for calculating crawl depth)
 * @param fileRepository Repository to check existing files
 * @param folderRepository Repository to link files to parent folders
 * @param fileMapper Mapper for DTO conversion
 * @param patternMatchingService Service for pattern-based analysis status determination
 * @param filePatterns Pattern set for file matching
 */
class FileProcessor(
    private val startPath: Path,
    private val fileRepository: FSFileRepository,
    private val folderRepository: FSFolderRepository,
    private val fileMapper: FSFileMapper,
    private val patternMatchingService: PatternMatchingService,
    private val filePatterns: PatternSet
) : ItemProcessor<Path, FSFileDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(FileProcessor::class.java)
        private const val MAX_TEXT_EXTRACT_SIZE = 10 * 1024 * 1024 // 10MB limit
    }

    // Cache for parent folder analysis status
    private val parentStatusCache = mutableMapOf<String, AnalysisStatus>()

    override fun process(item: Path): FSFileDTO? {
        val uri = item.toString()
        log.debug("Processing file: {}", uri)

        try {
            // Get file system last modified time
            val fsModTime = try {
                OffsetDateTime.ofInstant(
                    Files.getLastModifiedTime(item).toInstant(),
                    ZoneId.systemDefault()
                )
            } catch (e: Exception) {
                log.warn("Failed to get last modified time for: {}", uri, e)
                null
            }

            // Get file size
            val fileSize = try {
                item.fileSize()
            } catch (e: Exception) {
                log.warn("Failed to get file size for: {}", uri, e)
                0L
            }

            // Check if file already exists and get its ID for update
            val existingFile = fileRepository.findByUri(uri)

            val dto = FSFileDTO()
            dto.id = existingFile?.id // Set ID for update if exists
            dto.status = if (existingFile != null) Status.CURRENT else Status.NEW
            dto.uri = uri
            dto.label = item.name
            dto.type = "FILE"
            dto.size = fileSize
            dto.fsLastModified = fsModTime
            dto.crawlDepth = calculateCrawlDepth(item)

            // Determine AnalysisStatus using hierarchical pattern matching
            val parentStatus = getParentFolderAnalysisStatus(item)
            val analysisStatus = if (parentStatus != null) {
                patternMatchingService.determineFileAnalysisStatus(
                    path = uri,
                    filePatterns = filePatterns,
                    parentFolderStatus = parentStatus
                )
            } else {
                // No parent folder found - default to LOCATE
                log.warn("Parent folder not found for: {}, defaulting to LOCATE", uri)
                AnalysisStatus.LOCATE
            }
            dto.analysisStatus = analysisStatus

            // If file is marked as IGNORE, skip it entirely
            if (analysisStatus == AnalysisStatus.IGNORE) {
                log.debug("File marked as IGNORE, skipping: {}", uri)
                return null
            }

            // Extract text content based on analysis status
            if (analysisStatus == AnalysisStatus.INDEX || analysisStatus == AnalysisStatus.ANALYZE) {
                if (fileSize > MAX_TEXT_EXTRACT_SIZE) {
                    log.warn("File too large for text extraction: {} ({} bytes)", uri, fileSize)
                    dto.bodyText = "[File too large: ${fileSize} bytes]"
                    dto.bodySize = fileSize
                } else {
                    try {
                        // Simple text extraction - read as UTF-8
                        // TODO: Add Apache Tika for better format support (PDF, DOCX, etc.)
                        val text = item.readText(Charsets.UTF_8)

                        // Sanitize: Remove null bytes (0x00) which PostgreSQL doesn't accept
                        val sanitizedText = text.replace("\u0000", "")

                        if (sanitizedText != text) {
                            log.debug("Sanitized {} null bytes from: {}", text.length - sanitizedText.length, uri)
                        }

                        dto.bodyText = sanitizedText
                        dto.bodySize = sanitizedText.length.toLong()
                        log.debug("Extracted {} chars from: {}", sanitizedText.length, uri)
                    } catch (e: Exception) {
                        log.warn("Failed to extract text from: {}", uri, e)
                        dto.bodyText = "[Text extraction failed: ${e.message}]"
                        dto.bodySize = 0L
                    }
                }
            } else {
                // LOCATE level - metadata only, no text extraction
                log.debug("LOCATE level, skipping text extraction: {}", uri)
                dto.bodyText = null
                dto.bodySize = null
            }

            // Link to parent folder
            val parentFolder = item.parent?.let { parentPath ->
                folderRepository.findByUri(parentPath.toString())
            }
            dto.fsFolder = parentFolder?.id

            // Try to read file attributes (POSIX)
            try {
                val attrs = Files.readAttributes(item, PosixFileAttributes::class.java)
                dto.owner = attrs.owner()?.name
                dto.group = attrs.group()?.name
                dto.permissions = attrs.permissions()?.joinToString("") {
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
                }
            } catch (e: UnsupportedOperationException) {
                // Not a POSIX file system (e.g., Windows)
                log.debug("POSIX attributes not available for: {}", uri)
            } catch (e: Exception) {
                log.warn("Failed to read file attributes for: {}", uri, e)
            }

            // Set version for optimistic locking
            dto.version = 0L

            log.debug("Processed file DTO: uri={}, size={}, analysisStatus={}, bodySize={}",
                dto.uri, dto.size, dto.analysisStatus, dto.bodySize)
            return dto

        } catch (e: Exception) {
            log.error("Error processing file: {}", uri, e)
            // Return null to skip this file instead of failing entire batch
            return null
        }
    }

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
     * Get the parent folder's AnalysisStatus from cache or database.
     * Returns null if parent not found.
     */
    private fun getParentFolderAnalysisStatus(filePath: Path): AnalysisStatus? {
        val parent = filePath.parent ?: return null
        val parentUri = parent.toString()

        // Check cache first
        parentStatusCache[parentUri]?.let { return it }

        // Check database
        val parentFolder = folderRepository.findByUri(parentUri)
        return parentFolder?.analysisStatus?.also {
            // Cache for future lookups
            parentStatusCache[parentUri] = it
        }
    }
}
