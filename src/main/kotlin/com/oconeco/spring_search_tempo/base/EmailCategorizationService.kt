package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.domain.EmailCategory
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO

/**
 * Service for categorizing email messages.
 *
 * Analyzes email metadata (sender, subject, body) and assigns
 * a category with confidence score.
 */
interface EmailCategorizationService {

    /**
     * Categorize an email message.
     *
     * @param message The email message DTO to categorize
     * @return Categorization result with category and confidence
     */
    fun categorize(message: EmailMessageDTO): CategorizationResult

    /**
     * Categorize multiple messages in batch.
     *
     * @param messages List of messages to categorize
     * @return Map of message ID to categorization result
     */
    fun categorizeBatch(messages: List<EmailMessageDTO>): Map<Long, CategorizationResult>
}

/**
 * Result of email categorization.
 */
data class CategorizationResult(
    /**
     * The assigned category.
     */
    val category: EmailCategory,

    /**
     * Confidence score for the category assignment (0.0 to 1.0).
     * Higher values indicate stronger signal for the category.
     */
    val confidence: Double,

    /**
     * Human-readable reason for the categorization.
     * Useful for debugging and transparency.
     */
    val reason: String
)
