package com.oconeco.spring_search_tempo.batch.progressive

import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.service.TextAndMetadataResult
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Processor for text extraction (indexing).
 *
 * Uses Apache Tika to extract text and metadata from files.
 * Updates the FSFileDTO with:
 * - bodyText: Extracted text content
 * - bodySize: Original file size
 * - Metadata fields: author, title, contentType, etc.
 * - indexedAt: Timestamp of extraction
 * - indexError: Error message if extraction failed
 *
 * @param textExtractionService Service for Tika text extraction
 * @param maxTextSize Maximum text size to extract (default 10MB)
 */
class IndexingProcessor(
    private val textExtractionService: TextExtractionService,
    private val maxTextSize: Long = 10_000_000L
) : ItemProcessor<FSFileDTO, FSFileDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(IndexingProcessor::class.java)
    }

    // Statistics
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val skippedCount = AtomicInteger(0)
    private val totalBytesProcessed = AtomicLong(0)

    override fun process(item: FSFileDTO): FSFileDTO? {
        val uri = item.uri ?: return null
        val path = Path(uri)

        // Check if file exists
        if (!path.exists()) {
            log.debug("File not found, skipping: {}", uri)
            skippedCount.incrementAndGet()
            return null
        }

        // Check file size
        val fileSize = try {
            Files.size(path)
        } catch (e: Exception) {
            log.warn("Cannot read file size: {} - {}", uri, e.message)
            skippedCount.incrementAndGet()
            return null
        }

        item.bodySize = fileSize
        val now = OffsetDateTime.now()

        try {
            // Extract text and metadata using Tika
            when (val result = textExtractionService.extractTextAndMetadata(path, maxTextSize)) {
                is TextAndMetadataResult.Success -> {
                    // Update DTO with extracted content
                    item.bodyText = result.text
                    item.contentType = result.metadata.contentType
                    item.author = result.metadata.author
                    item.title = result.metadata.title
                    item.subject = result.metadata.subject
                    item.keywords = result.metadata.keywords
                    item.comments = result.metadata.comments
                    item.creationDate = result.metadata.creationDate
                    item.modifiedDate = result.metadata.modifiedDate
                    item.language = result.metadata.language
                    item.pageCount = result.metadata.pageCount

                    // Mark as successfully indexed
                    item.indexedAt = now
                    item.indexError = null

                    successCount.incrementAndGet()
                    totalBytesProcessed.addAndGet(fileSize)

                    log.debug("Indexed file: {} ({} chars)", uri, result.text.length)
                }

                is TextAndMetadataResult.Failure -> {
                    // Record the error but still mark as indexed (prevents retry loops)
                    item.indexedAt = now
                    item.indexError = result.error
                    item.accessDenied = result.error.contains("Access denied", ignoreCase = true) ||
                            result.error.contains("Permission denied", ignoreCase = true)

                    failureCount.incrementAndGet()

                    log.warn("Failed to extract text from {}: {}", uri, result.error)
                }
            }

            return item

        } catch (e: Exception) {
            // Unexpected error
            item.indexedAt = now
            item.indexError = "Unexpected error: ${e.message}"
            item.extractionError = true

            failureCount.incrementAndGet()

            log.error("Unexpected error indexing {}: {}", uri, e.message, e)
            return item
        }
    }

    /**
     * Get statistics about indexing progress.
     */
    fun getStats(): IndexingStats {
        return IndexingStats(
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            skippedCount = skippedCount.get(),
            totalBytesProcessed = totalBytesProcessed.get()
        )
    }
}

/**
 * Statistics about indexing progress.
 */
data class IndexingStats(
    val successCount: Int,
    val failureCount: Int,
    val skippedCount: Int,
    val totalBytesProcessed: Long
)
