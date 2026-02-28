package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.OneDriveAccountDTO
import com.oconeco.spring_search_tempo.base.model.OneDriveAccountSummaryDTO


interface OneDriveAccountService {

    fun count(): Long

    fun findAll(): List<OneDriveAccountDTO>

    fun findEnabled(): List<OneDriveAccountDTO>

    fun get(id: Long): OneDriveAccountDTO

    fun create(oneDriveAccountDTO: OneDriveAccountDTO): Long

    fun update(id: Long, oneDriveAccountDTO: OneDriveAccountDTO)

    fun delete(id: Long)

    /**
     * Store encrypted refresh token for an account.
     */
    fun storeTokens(id: Long, encryptedRefreshToken: String)

    /**
     * Get decrypted refresh token for an account.
     */
    fun getDecryptedRefreshToken(id: Long): String?

    /**
     * Update delta token after successful delta sync.
     */
    fun updateDeltaToken(id: Long, deltaToken: String?)

    /**
     * Clear delta token to force a full sync.
     */
    fun clearDeltaToken(id: Long)

    /**
     * Update drive info after connecting or syncing.
     */
    fun updateDriveInfo(id: Long, driveId: String, driveType: String?, quotaTotal: Long?, quotaUsed: Long?)

    /**
     * Record an error that occurred during sync.
     */
    fun recordError(id: Long, error: String)

    /**
     * Clear the last error after successful sync.
     */
    fun clearError(id: Long)

    /**
     * Find all accounts with summary info (item counts, active job progress).
     */
    fun findAllWithSummary(): List<OneDriveAccountSummaryDTO>

    /**
     * Get summary for a single account.
     */
    fun getSummary(id: Long): OneDriveAccountSummaryDTO

    /**
     * Find by Microsoft account ID.
     */
    fun findByMicrosoftAccountId(microsoftAccountId: String): OneDriveAccountDTO?

    /**
     * Update sync timestamp and item counts.
     */
    fun updateSyncStats(id: Long, totalItems: Long, totalSize: Long)

}
