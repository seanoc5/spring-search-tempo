package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.domain.EmailCategory
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.OffsetDateTime


interface EmailMessageService {

    fun count(): Long

    fun findAll(pageable: Pageable): Page<EmailMessageDTO>

    fun get(id: Long): EmailMessageDTO

    fun create(emailMessageDTO: EmailMessageDTO): Long

    fun update(id: Long, emailMessageDTO: EmailMessageDTO)

    fun delete(id: Long)

    fun existsByMessageId(messageId: String): Boolean

    /**
     * Check if a message with the given URI already exists.
     * Used for duplicate detection when Message-ID is not available.
     */
    fun existsByUri(uri: String): Boolean

    /**
     * Find which Message-IDs already exist in the database.
     * Used for batch duplicate detection during sync.
     */
    fun findExistingMessageIds(messageIds: Collection<String>): Set<String>

    fun getEmailMessageValues(): Map<Long, Long>

    /**
     * Find messages with non-null bodyText for chunking.
     * Used by batch processing to retrieve messages that need text chunking.
     */
    fun findMessagesWithBodyText(pageable: Pageable): Page<EmailMessageDTO>

    /**
     * Find messages with bodyText for a specific account.
     */
    fun findMessagesWithBodyTextByAccount(accountId: Long, pageable: Pageable): Page<EmailMessageDTO>

    /**
     * Search messages by subject, from address, or body text.
     */
    fun search(filter: String, pageable: Pageable): Page<EmailMessageDTO>

    /**
     * Count messages for a specific account.
     */
    fun countByAccount(accountId: Long): Long

    /**
     * Find messages needing body fetch (HEADERS_ONLY status) for an account.
     * Used by body enrichment pass.
     */
    fun findHeadersOnlyByAccount(accountId: Long, pageable: Pageable): Page<EmailMessageDTO>

    /**
     * Count messages needing body fetch for an account.
     */
    fun countHeadersOnlyByAccount(accountId: Long): Long

    /**
     * Update message body and mark as COMPLETE.
     * Used by body enrichment processor.
     */
    fun updateBodyAndComplete(id: Long, bodyText: String?, bodySize: Long?, hasAttachments: Boolean, attachmentCount: Int, attachmentNames: String?)

    /**
     * Find messages that need categorization.
     * Returns messages with fetchStatus=COMPLETE and categorizedAt=NULL.
     */
    fun findUncategorizedByAccount(accountId: Long, pageable: Pageable): Page<EmailMessageDTO>

    /**
     * Count uncategorized messages for an account.
     */
    fun countUncategorizedByAccount(accountId: Long): Long

    /**
     * Update categorization fields for a message.
     */
    fun updateCategorization(id: Long, category: EmailCategory, confidence: Double?, categorizedAt: OffsetDateTime?)

    /**
     * Mark a message as read.
     */
    fun markAsRead(id: Long)

    /**
     * Mark a message as unread.
     */
    fun markAsUnread(id: Long)

    /**
     * Toggle read status and return the new status.
     */
    fun toggleReadStatus(id: Long): Boolean

    /**
     * Count unread messages for an account.
     */
    fun countUnreadByAccount(accountId: Long): Long

    /**
     * Find "interesting" email messages for chunking.
     * Applies date cutoff, junk filter, and optional already-chunked filter.
     */
    fun findInterestingForChunking(
        accountId: Long,
        cutoffDate: OffsetDateTime,
        forceRefresh: Boolean,
        pageable: Pageable
    ): Page<EmailMessageDTO>

    /**
     * Get message with tags eagerly loaded.
     */
    fun getWithTags(id: Long): EmailMessageDTO

}
