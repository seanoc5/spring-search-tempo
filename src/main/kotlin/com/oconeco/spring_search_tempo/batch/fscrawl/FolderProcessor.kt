package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.config.PatternSet
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import com.oconeco.spring_search_tempo.base.service.PatternMatchingService
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.io.path.name

class FolderProcessor(
    private val startPath: Path,
    private val folderRepository: FSFolderRepository,
    private val folderMapper: FSFolderMapper,
    private val patternMatchingService: PatternMatchingService,
    private val folderPatterns: PatternSet
) : ItemProcessor<Path, FSFolderDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(FolderProcessor::class.java)
    }

    // Cache for parent analysis status to support hierarchical matching
    private val parentStatusCache = mutableMapOf<String, AnalysisStatus>()

    override fun process(item: Path): FSFolderDTO? {
        val uri = item.toString()
        log.debug("Processing folder: {}", uri)

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

        // Check if folder already exists
        val existingFolder = folderRepository.findByUri(uri)

        val dto = if (existingFolder != null) {
            // Folder exists - check for changes
            val savedFsModTime = existingFolder.fsLastModified
            val timestampsMatch = savedFsModTime != null && fsModTime != null &&
                savedFsModTime.isEqual(fsModTime)

            if (timestampsMatch && existingFolder.status == Status.CURRENT) {
                // No changes detected, skip this folder
                log.debug("Folder unchanged, skipping: {}", uri)
                return null
            }

            log.info("\t\tFolder exists, updating: {} (timestamps match: {}, status: {})",
                uri, timestampsMatch, existingFolder.status)

            val updatedDto = folderMapper.updateFSFolderDTO(existingFolder, FSFolderDTO())

            // Set status based on timestamp comparison
            updatedDto.status = if (timestampsMatch) {
                Status.CURRENT
            } else {
                Status.DIRTY
            }
            updatedDto.fsLastModified = fsModTime
            updatedDto
        } else {
            // New folder
            log.debug("New folder, creating: {}", uri)
            val newDto = FSFolderDTO()
            newDto.status = Status.NEW
            newDto.fsLastModified = fsModTime
            newDto
        }

        // Set basic properties
        dto.type = "FOLDER"
        dto.uri = uri
        dto.label = item.name
        dto.crawlDepth = calculateCrawlDepth(item)

        // Determine AnalysisStatus using hierarchical pattern matching
        val parentStatus = getParentAnalysisStatus(item)
        val analysisStatus = patternMatchingService.determineFolderAnalysisStatus(
            path = uri,
            patterns = folderPatterns,
            parentStatus = parentStatus
        )
        dto.analysisStatus = analysisStatus

        // Cache this folder's status for its children
        parentStatusCache[uri] = analysisStatus

        // If folder is marked as IGNORE, skip it entirely
        if (analysisStatus == AnalysisStatus.IGNORE) {
            log.debug("Folder marked as IGNORE, skipping: {}", uri)
            return null
        }

        // Set version for optimistic locking (0 for new, keep existing for updates)
        if (dto.version == null) {
            dto.version = 0L
        }

        // Try to read file attributes
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

        log.debug("Processed folder DTO: uri={}, id={}, crawlDepth={}",
            dto.uri, dto.id, dto.crawlDepth)
        return dto
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
     * Returns null if at root level or parent not found.
     */
    private fun getParentAnalysisStatus(path: Path): AnalysisStatus? {
        val parent = path.parent ?: return null
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
