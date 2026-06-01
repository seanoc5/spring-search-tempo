package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.EmailFolderDTO


interface EmailFolderService {

    fun count(): Long

    fun findAll(): List<EmailFolderDTO>

    fun findByAccount(accountId: Long): List<EmailFolderDTO>

    fun get(id: Long): EmailFolderDTO

    fun create(emailFolderDTO: EmailFolderDTO): Long

    fun update(id: Long, emailFolderDTO: EmailFolderDTO)

    fun delete(id: Long)

    fun getEmailFolderValues(): Map<Long, Long>

    /**
     * Find or create a folder for an account.
     * Used during sync to ensure folder exists before adding messages.
     */
    fun findOrCreate(accountId: Long, folderName: String, path: String): EmailFolderDTO

    /**
     * Update sync state after processing a folder.
     */
    fun updateSyncState(id: Long, lastUid: Long, messageCount: Long)

    /**
     * Reset sync state for a folder to trigger full resync.
     * Sets lastSyncUid to 0 and clears uidValidity.
     */
    fun resetSyncState(id: Long)

    /**
     * Reset sync state for all folders in an account.
     */
    fun resetSyncStateForAccount(accountId: Long)

    /**
     * Update UIDVALIDITY for a folder.
     * If UIDVALIDITY changed, returns true (indicating UIDs are invalid).
     */
    fun updateUidValidity(id: Long, newUidValidity: Long): Boolean

    /**
     * Toggle a folder's `syncEnabled` flag.
     *
     * Re-enabling a folder (false → true) resets its `lastSyncUid` to 0 so the
     * next sync pulls full history — per spec, the user expects a freshly
     * re-enabled folder to backfill, not to silently resume from the stale UID
     * cursor it had when last disabled.
     */
    fun setSyncEnabled(id: Long, enabled: Boolean)

    /**
     * Folders that should be visited by `EmailQuickSyncReader` for this account:
     * `syncEnabled = true` and not `\Noselect`. Returns folder paths in stable
     * order (path ascending) so job step naming is deterministic.
     */
    fun fetchTargets(accountId: Long): List<String>

}
