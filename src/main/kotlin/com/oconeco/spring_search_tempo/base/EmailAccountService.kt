package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.EmailAccountDTO
import com.oconeco.spring_search_tempo.base.model.EmailAccountSummaryDTO


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

    /**
     * Record the wall-clock time at which the per-account scheduler dispatched
     * a sync for this account. Used by the minute-tick scheduler to decide
     * which accounts are due without re-firing within the same cron boundary.
     */
    fun recordDispatched(id: Long, dispatchedAt: java.time.OffsetDateTime)

    /**
     * Encrypt and store an IMAP password for the account. Pass a blank string to clear the stored
     * password (after which the account has no credential and is skipped by the scheduler until
     * a new password is set). The plaintext is never logged or persisted.
     */
    fun setPassword(id: Long, plaintext: String)

    /**
     * Decrypt and return the stored IMAP password, or `null` if no encrypted password has been set
     * for this account. Never logs the plaintext.
     */
    fun getPassword(id: Long): String?

    /**
     * Whether the account has a stored encrypted password (does NOT decrypt).
     */
    fun hasPassword(id: Long): Boolean

    /**
     * Find all accounts with summary info (folder/message counts, active job progress).
     */
    fun findAllWithSummary(): List<EmailAccountSummaryDTO>

    /**
     * Get summary for a single account (for HTMX partial refresh).
     */
    fun getSummary(id: Long): EmailAccountSummaryDTO

    // Ownership-filtered methods for multi-tenancy

    /**
     * Find all accounts owned by the current user.
     */
    fun findAllForCurrentUser(): List<EmailAccountDTO>

    /**
     * Find enabled accounts owned by the current user.
     */
    fun findEnabledForCurrentUser(): List<EmailAccountDTO>

    /**
     * Find all accounts with summary info, filtered by current user's ownership.
     */
    fun findAllWithSummaryForCurrentUser(): List<EmailAccountSummaryDTO>

}
