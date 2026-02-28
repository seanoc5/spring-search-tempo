package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailCategorizationService
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.time.OffsetDateTime

/**
 * ItemProcessor for email categorization.
 *
 * Uses the EmailCategorizationService to assign a category and confidence
 * to each email message.
 */
class EmailCategorizationProcessor(
    private val categorizationService: EmailCategorizationService
) : ItemProcessor<EmailMessageDTO, EmailMessageDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailCategorizationProcessor::class.java)
    }

    override fun process(message: EmailMessageDTO): EmailMessageDTO {
        try {
            val result = categorizationService.categorize(message)

            message.category = result.category
            message.categoryConfidence = result.confidence
            message.categorizedAt = OffsetDateTime.now()

            log.trace("Categorized message {} as {} (confidence={}, reason={})",
                message.id, result.category, result.confidence, result.reason)

            return message
        } catch (e: Exception) {
            log.warn("Failed to categorize message {}: {}", message.id, e.message)
            // Still return the message but with UNCATEGORIZED category
            // This prevents blocking the pipeline on categorization failures
            message.categorizedAt = OffsetDateTime.now()
            return message
        }
    }
}
