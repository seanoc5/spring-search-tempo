package com.oconeco.spring_search_tempo.batch.progressive

import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicLong

/**
 * Writer for indexed files.
 *
 * Persists extracted text and metadata back to the database.
 * Updates:
 * - bodyText, bodySize
 * - Tika metadata fields
 * - indexedAt timestamp
 * - indexError if extraction failed
 */
class IndexingWriter(
    private val fileRepository: FSFileRepository,
    private val fileMapper: FSFileMapper
) : ItemWriter<FSFileDTO>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(IndexingWriter::class.java)
    }

    // Statistics
    private val filesIndexed = AtomicLong(0)
    private val filesWithErrors = AtomicLong(0)
    private val totalCharsExtracted = AtomicLong(0)

    override fun afterStep(stepExecution: StepExecution): org.springframework.batch.core.ExitStatus {
        stepExecution.executionContext.putLong("filesIndexed", filesIndexed.get())
        stepExecution.executionContext.putLong("filesWithErrors", filesWithErrors.get())
        stepExecution.executionContext.putLong("totalCharsExtracted", totalCharsExtracted.get())

        log.info(
            "Indexing complete: {} files indexed, {} with errors, {} chars extracted",
            filesIndexed.get(), filesWithErrors.get(), totalCharsExtracted.get()
        )

        return org.springframework.batch.core.ExitStatus.COMPLETED
    }

    @Transactional
    override fun write(chunk: Chunk<out FSFileDTO>) {
        val ids = chunk.items.mapNotNull { it.id }
        if (ids.isEmpty()) return

        val entities = fileRepository.findAllById(ids).associateBy { it.id }

        chunk.items.forEach { dto ->
            val entity = entities[dto.id] ?: run {
                log.warn("File {} not found for indexing update", dto.id)
                return@forEach
            }

            // Update entity from DTO
            entity.bodyText = dto.bodyText
            entity.bodySize = dto.bodySize
            entity.contentType = dto.contentType
            entity.author = dto.author
            entity.title = dto.title
            entity.subject = dto.subject
            entity.keywords = dto.keywords
            entity.comments = dto.comments
            entity.creationDate = dto.creationDate
            entity.modifiedDate = dto.modifiedDate
            entity.language = dto.language
            entity.pageCount = dto.pageCount
            entity.indexedAt = dto.indexedAt
            entity.indexError = dto.indexError
            entity.status = Status.CURRENT

            // Track statistics
            if (dto.indexError != null) {
                filesWithErrors.incrementAndGet()
            } else {
                filesIndexed.incrementAndGet()
                dto.bodyText?.let { totalCharsExtracted.addAndGet(it.length.toLong()) }
            }
        }

        fileRepository.saveAll(entities.values)
        log.debug("Saved indexing results for {} files", entities.size)
    }
}
