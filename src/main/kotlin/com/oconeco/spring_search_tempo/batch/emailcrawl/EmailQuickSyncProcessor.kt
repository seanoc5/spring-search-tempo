package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.service.EmailTextExtractionService
import com.oconeco.spring_search_tempo.base.service.EmailTextResult
import jakarta.mail.Address
import jakarta.mail.Message
import jakarta.mail.Multipart
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.time.OffsetDateTime
import java.time.ZoneOffset


/**
 * Processor that extracts email content and creates EmailMessageDTO.
 *
 * Handles:
 * - Duplicate detection by Message-ID
 * - Envelope data extraction (from, to, subject, dates)
 * - Body text extraction (plain text or HTML conversion)
 * - Attachment detection
 */
class EmailQuickSyncProcessor(
    private val emailMessageService: EmailMessageService,
    private val emailTextExtractionService: EmailTextExtractionService,
    private val objectMapper: ObjectMapper = ObjectMapper()
) : ItemProcessor<ImapMessageWrapper, EmailMessageDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailQuickSyncProcessor::class.java)
    }

    private var processedCount = 0
    private var skippedCount = 0

    override fun process(item: ImapMessageWrapper): EmailMessageDTO? {
        val message = item.message

        try {
            // Get Message-ID for duplicate detection
            val messageId = message.getHeader("Message-ID")?.firstOrNull()?.trim()

            // Skip if message already exists
            if (messageId != null && emailMessageService.existsByMessageId(messageId)) {
                skippedCount++
                log.debug("Skipping existing message: {}", messageId)
                return null
            }

            processedCount++
            if (processedCount % 50 == 0) {
                log.info("Processed {} emails ({} skipped)", processedCount, skippedCount)
            }

            // Create DTO with envelope data
            val dto = EmailMessageDTO().apply {
                this.messageId = messageId
                this.imapUid = item.uid
                this.subject = message.subject?.take(1000)
                this.fromAddress = formatAddress(message.from?.firstOrNull())
                this.toAddresses = toJsonArray(message.getRecipients(Message.RecipientType.TO))
                this.ccAddresses = toJsonArray(message.getRecipients(Message.RecipientType.CC))
                this.bccAddresses = toJsonArray(message.getRecipients(Message.RecipientType.BCC))
                this.sentDate = message.sentDate?.toInstant()?.atOffset(ZoneOffset.UTC)
                this.receivedDate = message.receivedDate?.toInstant()?.atOffset(ZoneOffset.UTC)
                this.contentType = message.contentType?.substringBefore(";")?.trim()

                // Threading headers
                this.inReplyTo = message.getHeader("In-Reply-To")?.firstOrNull()?.trim()
                this.references = toJsonArray(message.getHeader("References"))

                // Email account and folder references
                this.emailAccount = item.accountId
                // emailFolder will be set by writer based on folder lookup

                // Standard fields
                this.analysisStatus = AnalysisStatus.INDEX
                this.status = Status.NEW
                this.uri = "email://${item.accountId}/${item.folderName}/${item.uid}"
                this.label = this.subject
                this.version = 0L
            }

            // Extract text content
            when (val result = emailTextExtractionService.extractText(message)) {
                is EmailTextResult.Success -> {
                    dto.bodyText = result.text
                    dto.bodySize = result.text.length.toLong()
                }
                is EmailTextResult.Failure -> {
                    dto.bodyText = "[Extraction failed: ${result.error}]"
                    dto.bodySize = 0
                    log.warn("Text extraction failed for message {}: {}", messageId, result.error)
                }
            }

            // Check for attachments
            if (message.content is Multipart) {
                val attachments = emailTextExtractionService.extractAttachmentInfo(message)
                dto.hasAttachments = attachments.isNotEmpty()
                dto.attachmentCount = attachments.size
                dto.attachmentNames = if (attachments.isNotEmpty()) {
                    objectMapper.writeValueAsString(attachments)
                } else null
            }

            // Set size based on body
            dto.size = dto.bodySize

            return dto

        } catch (e: Exception) {
            log.error("Error processing message UID {}: {}", item.uid, e.message, e)
            return null
        }
    }

    private fun formatAddress(address: Address?): String? {
        return address?.toString()?.take(500)
    }

    private fun toJsonArray(addresses: Array<out Address>?): String? {
        if (addresses.isNullOrEmpty()) return null
        val list = addresses.map { it.toString().take(500) }
        return objectMapper.writeValueAsString(list)
    }

    private fun toJsonArray(headers: Array<String>?): String? {
        if (headers.isNullOrEmpty()) return null
        val list = headers.flatMap { it.split(Regex("\\s+")) }.map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isNotEmpty()) objectMapper.writeValueAsString(list) else null
    }

    fun getProcessedCount(): Int = processedCount
    fun getSkippedCount(): Int = skippedCount
}
