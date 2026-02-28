package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter

/**
 * ItemWriter for email categorization.
 *
 * Persists categorization results (category, confidence, categorizedAt)
 * to the database.
 */
class EmailCategorizationWriter(
    private val emailMessageService: EmailMessageService
) : ItemWriter<EmailMessageDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailCategorizationWriter::class.java)
    }

    override fun write(items: Chunk<out EmailMessageDTO>) {
        log.debug("Writing categorization for {} messages", items.size())

        items.forEach { message ->
            try {
                emailMessageService.updateCategorization(
                    message.id!!,
                    message.category,
                    message.categoryConfidence,
                    message.categorizedAt
                )
            } catch (e: Exception) {
                log.error("Failed to save categorization for message {}: {}", message.id, e.message)
                // Continue with other messages
            }
        }

        log.debug("Wrote categorization for {} messages", items.size())
    }
}
