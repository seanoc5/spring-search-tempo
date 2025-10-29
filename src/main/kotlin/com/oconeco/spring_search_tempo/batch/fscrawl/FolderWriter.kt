package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter

class FolderWriter(
    private val folderService: FSFolderService
) : ItemWriter<FSFolderDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(FolderWriter::class.java)
    }

    override fun write(chunk: Chunk<out FSFolderDTO>) {
        var created = 0
        var updated = 0
        var failed = 0

        chunk.items.forEach { dto ->
            try {
                if (dto.id != null) {
                    // Update existing folder
                    folderService.update(dto.id!!, dto)
                    updated++
                    log.debug("Updated folder: uri={}, id={}, status={}", dto.uri, dto.id, dto.status)
                } else {
                    // Create new folder
                    val id = folderService.create(dto)
                    created++
                    log.debug("Created folder: uri={}, id={}, status={}", dto.uri, id, dto.status)
                }
            } catch (e: Exception) {
                failed++
                log.error("Failed to save folder: uri={}", dto.uri, e)
                // Continue processing remaining items instead of failing the entire chunk
            }
        }

        log.info("Folder write complete: {} created, {} updated, {} failed (skipped folders not counted here)",
            created, updated, failed)
    }
}
