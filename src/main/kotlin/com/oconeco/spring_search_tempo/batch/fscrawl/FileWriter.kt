package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter

/**
 * ItemWriter that saves FSFile records to the database.
 *
 * @param fileService Service for creating/updating files
 */
class FileWriter(
    private val fileService: FSFileService
) : ItemWriter<FSFileDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(FileWriter::class.java)
    }

    override fun write(chunk: Chunk<out FSFileDTO>) {
        var created = 0
        var updated = 0
        var failed = 0

        chunk.items.forEach { dto ->
            try {
                if (dto.id != null) {
                    // Update existing file
                    fileService.update(dto.id!!, dto)
                    updated++
                    log.debug("Updated file: uri={}, id={}, status={}, bodySize={}",
                        dto.uri, dto.id, dto.status, dto.bodySize)
                } else {
                    // Create new file
                    val id = fileService.create(dto)
                    created++
                    log.debug("Created file: uri={}, id={}, status={}, bodySize={}",
                        dto.uri, id, dto.status, dto.bodySize)
                }
            } catch (e: Exception) {
                failed++
                log.error("Failed to save file: uri={}", dto.uri, e)
                // Continue processing remaining items instead of failing the entire chunk
            }
        }

        log.info("File write complete: {} created, {} updated, {} failed (skipped files not counted here)",
            created, updated, failed)
    }
}
