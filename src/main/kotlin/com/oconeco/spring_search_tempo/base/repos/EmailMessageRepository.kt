package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.EmailCategory
import com.oconeco.spring_search_tempo.base.domain.EmailMessage
import com.oconeco.spring_search_tempo.base.domain.FetchStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime


interface EmailMessageRepository : JpaRepository<EmailMessage, Long> {

    fun findByMessageId(messageId: String): EmailMessage?

    fun findByUri(uri: String): EmailMessage?

    fun existsByMessageId(messageId: String): Boolean

    fun findByBodyTextIsNotNull(pageable: Pageable): Page<EmailMessage>

    fun findByBodyTextIsNotNullAndEmailAccountId(accountId: Long, pageable: Pageable): Page<EmailMessage>

    fun findByEmailAccountIdAndImapUidGreaterThan(accountId: Long, uid: Long): List<EmailMessage>

    fun findByEmailFolderId(folderId: Long): List<EmailMessage>

    /**
     * Search messages by subject, from address, or body text (case-insensitive).
     */
    @Query("""
        SELECT e FROM EmailMessage e
        WHERE LOWER(e.subject) LIKE LOWER(CONCAT('%', :filter, '%'))
           OR LOWER(e.fromAddress) LIKE LOWER(CONCAT('%', :filter, '%'))
           OR LOWER(e.bodyText) LIKE LOWER(CONCAT('%', :filter, '%'))
    """)
    fun search(filter: String, pageable: Pageable): Page<EmailMessage>

    fun countByEmailAccountId(accountId: Long): Long

    fun countByEmailFolderId(folderId: Long): Long

    /**
     * Count unread messages for an account.
     */
    fun countByEmailAccountIdAndIsRead(accountId: Long, isRead: Boolean): Long

    /**
     * Find all existing Message-IDs from a set of candidates.
     * Used for batch duplicate detection during sync.
     */
    @Query("SELECT e.messageId FROM EmailMessage e WHERE e.messageId IN :messageIds")
    fun findExistingMessageIds(messageIds: Collection<String>): List<String>

    /**
     * Find messages by fetch status for an account.
     * Used for body enrichment pass (fetching bodies for HEADERS_ONLY messages).
     */
    fun findByEmailAccountIdAndFetchStatus(
        accountId: Long,
        fetchStatus: FetchStatus,
        pageable: Pageable
    ): Page<EmailMessage>

    /**
     * Count messages needing body fetch for an account.
     */
    fun countByEmailAccountIdAndFetchStatus(accountId: Long, fetchStatus: FetchStatus): Long

    /**
     * Delete all messages for a folder.
     */
    @Modifying
    fun deleteByEmailFolderId(folderId: Long): Int

    /**
     * Delete all messages for an account.
     */
    @Modifying
    fun deleteByEmailAccountId(accountId: Long): Int

    /**
     * Find messages that need categorization (COMPLETE but not categorized).
     */
    fun findByEmailAccountIdAndFetchStatusAndCategorizedAtIsNull(
        accountId: Long,
        fetchStatus: FetchStatus,
        pageable: Pageable
    ): Page<EmailMessage>

    /**
     * Count uncategorized messages for an account.
     */
    fun countByEmailAccountIdAndFetchStatusAndCategorizedAtIsNull(
        accountId: Long,
        fetchStatus: FetchStatus
    ): Long

    /**
     * Update categorization fields for a message.
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE EmailMessage e
        SET e.category = :category,
            e.categoryConfidence = :confidence,
            e.categorizedAt = :categorizedAt
        WHERE e.id = :id
    """)
    fun updateCategorization(
        @Param("id") id: Long,
        @Param("category") category: EmailCategory,
        @Param("confidence") confidence: Double?,
        @Param("categorizedAt") categorizedAt: OffsetDateTime?
    )

    /**
     * Update read status for a message.
     */
    @Modifying
    @Transactional
    @Query("UPDATE EmailMessage e SET e.isRead = :isRead WHERE e.id = :id")
    fun updateReadStatus(@Param("id") id: Long, @Param("isRead") isRead: Boolean)

    /**
     * Direct update for body enrichment - avoids SELECT+UPDATE pattern.
     * PERFORMANCE: Single UPDATE statement vs findById + save (2 statements).
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE EmailMessage e SET
            e.bodyText = :bodyText,
            e.bodySize = :bodySize,
            e.hasAttachments = :hasAttachments,
            e.attachmentCount = :attachmentCount,
            e.attachmentNames = :attachmentNames,
            e.fetchStatus = com.oconeco.spring_search_tempo.base.domain.FetchStatus.COMPLETE
        WHERE e.id = :id
    """)
    fun updateBodyDirect(
        @Param("id") id: Long,
        @Param("bodyText") bodyText: String?,
        @Param("bodySize") bodySize: Long?,
        @Param("hasAttachments") hasAttachments: Boolean,
        @Param("attachmentCount") attachmentCount: Int,
        @Param("attachmentNames") attachmentNames: String?
    )

    /**
     * Find message with tags eagerly loaded.
     */
    @Query("SELECT e FROM EmailMessage e LEFT JOIN FETCH e.tags WHERE e.id = :id")
    fun findByIdWithTags(@Param("id") id: Long): EmailMessage?

    /**
     * Find "interesting" email messages for chunking.
     * Filters by: account, non-null body, received within cutoff date, no 'junk' tag,
     * and optionally skips messages that already have content chunks.
     */
    @Query("""
        SELECT e FROM EmailMessage e
        WHERE e.emailAccount.id = :accountId
          AND e.bodyText IS NOT NULL
          AND e.receivedDate >= :cutoffDate
          AND NOT EXISTS (SELECT 1 FROM e.tags t WHERE t.name = 'junk')
          AND (:forceRefresh = true OR NOT EXISTS (
              SELECT 1 FROM ContentChunk cc WHERE cc.emailMessage = e
          ))
    """)
    fun findInterestingForChunking(
        @Param("accountId") accountId: Long,
        @Param("cutoffDate") cutoffDate: OffsetDateTime,
        @Param("forceRefresh") forceRefresh: Boolean,
        pageable: Pageable
    ): Page<EmailMessage>

    /**
     * Find messages by tag with pagination.
     */
    @Query(
        "SELECT DISTINCT e FROM EmailMessage e JOIN e.tags t WHERE t.id = :tagId",
        countQuery = "SELECT COUNT(DISTINCT e) FROM EmailMessage e JOIN e.tags t WHERE t.id = :tagId"
    )
    fun findByTagId(@Param("tagId") tagId: Long, pageable: Pageable): Page<EmailMessage>

    /**
     * Count messages with a specific tag.
     */
    @Query("SELECT COUNT(DISTINCT e) FROM EmailMessage e JOIN e.tags t WHERE t.id = :tagId")
    fun countByTagId(@Param("tagId") tagId: Long): Long

}
