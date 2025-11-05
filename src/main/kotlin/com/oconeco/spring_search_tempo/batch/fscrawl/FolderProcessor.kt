package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
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
    private val folderMapper: FSFolderMapper
) : ItemProcessor<Path, FSFolderDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(FolderProcessor::class.java)
    }

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
}
