package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import java.time.OffsetDateTime


interface EmailAccountService {

    fun count(): Long

    fun findAll(): List<EmailAccountDTO>

    fun findEnabled(): List<EmailAccountDTO>

    fun get(id: Long): EmailAccountDTO

    fun create(emailAccountDTO: EmailAccountDTO): Long

    fun update(id: Long, emailAccountDTO: EmailAccountDTO)

    fun delete(id: Long)

    fun emailExists(email: String): Boolean

    /**
     * Find an account by email address.
     */
    fun findByEmail(email: String): EmailAccountDTO?

    fun getEmailAccountValues(): Map<Long, Long>

    /**
     * Update quick sync state (INBOX and Sent UIDs).
     */
    fun updateQuickSyncState(id: Long, inboxUid: Long?, sentUid: Long?)

    /**
     * Update full sync state after weekly exhaustive sync.
     */
    fun updateFullSyncState(id: Long, folderCount: Int)

    /**
     * Record an error that occurred during sync.
     */
    fun recordError(id: Long, error: String)

    /**
     * Clear the last error after successful sync.
     */
    fun clearError(id: Long)

}
