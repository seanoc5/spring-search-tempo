package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.OneDriveAccountService
import com.oconeco.spring_search_tempo.base.config.OneDriveConfiguration
import com.oconeco.spring_search_tempo.base.model.OneDriveItemDTO
import com.oconeco.spring_search_tempo.base.service.OneDriveConnectionService
import com.oconeco.spring_search_tempo.base.service.TextAndMetadataResult
import com.oconeco.spring_search_tempo.base.service.TextExtractionService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemProcessor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger


/**
 * Pass 2 Processor: Downloads OneDrive files and extracts text using Tika.
 *
 * For each item:
 * 1. Get DriveItem from Graph to obtain @microsoft.graph.downloadUrl
 * 2. Download file to temp directory
 * 3. Run TextExtractionService.extractTextAndMetadata()
 * 4. Return OneDriveContentResult
 * 5. Clean up temp file
 */
class OneDriveContentProcessor(
    private val connectionService: OneDriveConnectionService,
    private val accountService: OneDriveAccountService,
    private val textExtractionService: TextExtractionService,
    private val config: OneDriveConfiguration
) : ItemProcessor<OneDriveItemDTO, OneDriveContentResult>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveContentProcessor::class.java)
    }

    private val httpClient = HttpClient.newHttpClient()
    private val processedCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)
    private val maxDownloadBytes = config.maxDownloadSizeMb.toLong() * 1024 * 1024

    override fun beforeStep(stepExecution: StepExecution) {
        // Ensure temp directory exists
        val tempDir = Path.of(config.downloadTempDir)
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir)
            log.info("Created OneDrive download temp directory: {}", tempDir)
        }
    }

    override fun process(item: OneDriveItemDTO): OneDriveContentResult? {
        val itemId = item.id ?: return null
        val graphItemId = item.graphItemId ?: return null
        val driveId = item.driveId ?: return null

        // Skip folders
        if (item.isFolder) {
            return null
        }

        // Skip files exceeding size limit
        val fileSize = item.size ?: 0L
        if (fileSize > maxDownloadBytes) {
            log.info("Skipping large file {} ({} bytes > {} MB limit): {}",
                graphItemId, fileSize, config.maxDownloadSizeMb, item.itemName)
            return OneDriveContentResult(
                itemId = itemId,
                failed = true,
                errorMessage = "File size ($fileSize bytes) exceeds limit (${config.maxDownloadSizeMb} MB)"
            )
        }

        var tempFile: Path? = null
        try {
            // Get download URL from Graph API
            val client = connectionService.getGraphClient(item.oneDriveAccount!!)
            val graphItem = client.drives().byDriveId(driveId).items().byDriveItemId(graphItemId).get()

            val downloadUrl = graphItem.additionalData?.get("@microsoft.graph.downloadUrl") as? String
            if (downloadUrl == null) {
                log.warn("No download URL for item {}: {}", graphItemId, item.itemName)
                return OneDriveContentResult(
                    itemId = itemId,
                    failed = true,
                    errorMessage = "No download URL available"
                )
            }

            // Download to temp file
            val extension = item.itemName?.substringAfterLast('.', "") ?: ""
            val suffix = if (extension.isNotEmpty()) ".$extension" else ""
            tempFile = Files.createTempFile(Path.of(config.downloadTempDir), "od-", suffix)

            val request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() != 200) {
                log.error("Download failed for item {} (HTTP {}): {}",
                    graphItemId, response.statusCode(), item.itemName)
                return OneDriveContentResult(
                    itemId = itemId,
                    failed = true,
                    errorMessage = "Download failed: HTTP ${response.statusCode()}"
                )
            }

            response.body().use { inputStream ->
                Files.copy(inputStream, tempFile!!, StandardCopyOption.REPLACE_EXISTING)
            }

            // Extract text and metadata using Tika
            val result = textExtractionService.extractTextAndMetadata(tempFile!!)

            val newCount = processedCount.incrementAndGet()
            if (newCount % 20 == 0) {
                log.info("Downloaded and extracted {} OneDrive files ({} errors)",
                    newCount, errorCount.get())
            }

            return when (result) {
                is TextAndMetadataResult.Success -> OneDriveContentResult(
                    itemId = itemId,
                    bodyText = result.text,
                    bodySize = result.text.length.toLong(),
                    contentType = result.metadata.contentType ?: item.mimeType,
                    author = result.metadata.author,
                    title = result.metadata.title,
                    pageCount = result.metadata.pageCount
                )
                is TextAndMetadataResult.Failure -> {
                    log.warn("Text extraction failed for {}: {}", item.itemName, result.error)
                    OneDriveContentResult(
                        itemId = itemId,
                        bodyText = "[Extraction failed: ${result.error}]",
                        bodySize = 0,
                        contentType = item.mimeType
                    )
                }
            }

        } catch (e: Exception) {
            log.error("Error processing OneDrive item {} ({}): {}",
                graphItemId, item.itemName, e.message, e)
            errorCount.incrementAndGet()
            return OneDriveContentResult(
                itemId = itemId,
                failed = true,
                errorMessage = "Processing error: ${e.message}"
            )
        } finally {
            // Clean up temp file
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile)
                } catch (e: Exception) {
                    log.warn("Failed to delete temp file {}: {}", tempFile, e.message)
                }
            }
        }
    }

    override fun afterStep(stepExecution: StepExecution): org.springframework.batch.core.ExitStatus {
        log.info("OneDrive content download complete: {} processed, {} errors", processedCount.get(), errorCount.get())
        return org.springframework.batch.core.ExitStatus.COMPLETED
    }

    fun getProcessedCount(): Int = processedCount.get()
    fun getErrorCount(): Int = errorCount.get()
}
