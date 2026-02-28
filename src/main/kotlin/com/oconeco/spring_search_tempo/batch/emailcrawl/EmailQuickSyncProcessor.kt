package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FetchStatus
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import jakarta.mail.Address
import jakarta.mail.Message
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.time.ZoneOffset


/**
 * Pass 1 Processor: Extracts email HEADERS ONLY (no body fetch).
 *
 * This is the fast pass that can process thousands of emails quickly because
 * it only uses prefetched envelope data - no IMAP body fetch needed.
 *
 * Handles:
 * - Envelope data extraction (from, to, subject, dates)
 * - Threading headers (In-Reply-To, References)
 * - Sets fetchStatus = HEADERS_ONLY
 *
 * Body fetching is done in Pass 2 (BodyEnrichmentProcessor) which can be parallelized.
 *
 * Note: Duplicate detection is done in EmailQuickSyncReader for efficiency
 * (batch query instead of per-message check).
 */
class EmailQuickSyncProcessor(
    private val objectMapper: ObjectMapper = ObjectMapper()
) : ItemProcessor<ImapMessageWrapper, EmailMessageDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailQuickSyncProcessor::class.java)
    }

    private var processedCount = 0

    override fun process(item: ImapMessageWrapper): EmailMessageDTO? {
        val message = item.message

        try {
            // Get Message-ID (headers already prefetched by Reader)
            val messageId = message.getHeader("Message-ID")?.firstOrNull()?.trim()

            processedCount++
            if (processedCount % 100 == 0) {
                log.info("Processed {} email headers", processedCount)
            }

            // Create DTO with envelope data ONLY (no body fetch - that's Pass 2)
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

                // Mark as headers-only - body will be fetched in Pass 2
                this.fetchStatus = FetchStatus.HEADERS_ONLY
            }

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
}
