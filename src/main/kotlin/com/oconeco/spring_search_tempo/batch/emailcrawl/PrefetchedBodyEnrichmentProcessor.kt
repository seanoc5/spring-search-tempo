package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.service.AttachmentExtraction
import com.oconeco.spring_search_tempo.base.service.EmailAttachmentExtractionService
import com.oconeco.spring_search_tempo.base.service.EmailTextExtractionService
import com.oconeco.spring_search_tempo.base.service.EmailTextResult
import jakarta.mail.Multipart
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemProcessor


/**
 * Pass 2 Processor (default): extracts text and attachments from an IMAP
 * message whose body has already been bulk-prefetched by
 * [PrefetchingBodyEnrichmentReader].
 *
 * No IMAP round-trips happen here — [PrefetchedBodyMessage.message] is a
 * cached [jakarta.mail.Message] handle. This is the per-item half of issue
 * #58: by moving the network FETCH into the reader (batched) and reducing
 * the processor to pure CPU, we collapse ~50 round-trips into one.
 */
class PrefetchedBodyEnrichmentProcessor(
    private val emailTextExtractionService: EmailTextExtractionService,
    private val emailAttachmentExtractionService: EmailAttachmentExtractionService,
    private val objectMapper: ObjectMapper = ObjectMapper()
) : ItemProcessor<PrefetchedBodyMessage, BodyEnrichmentResult>, StepExecutionListener {

    companion object {
        private val log = LoggerFactory.getLogger(PrefetchedBodyEnrichmentProcessor::class.java)
    }

    private var processedCount = 0
    private var errorCount = 0

    override fun process(item: PrefetchedBodyMessage): BodyEnrichmentResult? {
        val messageId = item.dto.id ?: return null
        val message = item.message

        return try {
            var bodyText: String? = null
            var bodySize: Long? = null

            when (val result = emailTextExtractionService.extractText(message)) {
                is EmailTextResult.Success -> {
                    bodyText = result.text
                    bodySize = result.text.length.toLong()
                }
                is EmailTextResult.Failure -> {
                    bodyText = "[Extraction failed: ${result.error}]"
                    bodySize = 0
                    log.warn("Body extraction failed for message {}: {}", messageId, result.error)
                }
            }

            var hasAttachments = false
            var attachmentCount = 0
            var attachmentNames: String? = null
            var attachmentExtractions: List<AttachmentExtraction> = emptyList()

            try {
                if (message.content is Multipart) {
                    val attachments = emailTextExtractionService.extractAttachmentInfo(message)
                    hasAttachments = attachments.isNotEmpty()
                    attachmentCount = attachments.size
                    attachmentNames = if (attachments.isNotEmpty()) {
                        objectMapper.writeValueAsString(attachments)
                    } else null

                    if (hasAttachments) {
                        attachmentExtractions = emailAttachmentExtractionService.extractAttachments(message)
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to extract attachments for message {}: {}", messageId, e.message)
            }

            processedCount++
            if (processedCount % 50 == 0) {
                log.info("Extracted {} prefetched email bodies ({} errors)", processedCount, errorCount)
            }

            BodyEnrichmentResult(
                messageId = messageId,
                bodyText = bodyText,
                bodySize = bodySize,
                hasAttachments = hasAttachments,
                attachmentCount = attachmentCount,
                attachmentNames = attachmentNames,
                attachmentExtractions = attachmentExtractions
            )
        } catch (e: Exception) {
            log.error("Error extracting body for prefetched message {}: {}", messageId, e.message, e)
            errorCount++
            null
        }
    }

    override fun afterStep(stepExecution: StepExecution): ExitStatus {
        log.info(
            "Prefetched body enrichment complete: {} processed, {} errors",
            processedCount, errorCount
        )
        return stepExecution.exitStatus
    }

    fun getProcessedCount(): Int = processedCount
    fun getErrorCount(): Int = errorCount
}
