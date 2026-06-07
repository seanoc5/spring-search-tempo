package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.EmailFolderDTO


/**
 * Outcome of comparing a folder's stored UIDVALIDITY against what the server
 * reports on SELECT/EXAMINE. See [EmailFolderService.observeUidValidity].
 */
sealed class UidValidityObservation {
    /** Stored value matched the server, or this is the first observation. */
    data object Unchanged : UidValidityObservation()

    /**
     * Stored value disagreed with the server — the folder was just marked
     * with `uidValidityMismatchAt` and sync MUST NOT proceed.
     *
     * @property storedUidValidity the value we had on file before the mismatch.
     * @property serverUidValidity the value the server reported.
     */
    data class Mismatch(
        val storedUidValidity: Long,
        val serverUidValidity: Long
    ) : UidValidityObservation()
}


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
     *
     * Issue #84: behaviour changed from auto-reset to hard-stop. The three
     * outcomes are:
     *
     *  - `Unchanged` — stored value matches the server (normal path), or this
     *    is the first time we've seen this folder and we just persisted the
     *    server's current value.
     *  - `Mismatch`  — stored value disagrees with the server. The folder is
     *    marked with `uidValidityMismatchAt = now()` and the caller MUST
     *    halt sync for this folder. The stored `lastSyncUid` is intentionally
     *    NOT reset so reconcile can map old UIDs by Message-ID before the
     *    cursor changes.
     *
     * The PO-stated preference is hard-stop with UI confirmation rather than
     * silent auto-recovery — UIDVALIDITY rotations are rare and diagnostic.
     */
    fun observeUidValidity(id: Long, serverUidValidity: Long): UidValidityObservation

    /**
     * Clear the `uidValidityMismatchAt` flag after a successful reconcile,
     * and update the stored `uidValidity` to the server's current value so
     * subsequent syncs proceed normally.
     */
    fun clearUidValidityMismatch(id: Long, newUidValidity: Long)

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

    /**
     * Issue #84: count of folders the operator has not yet opted into. Used
     * by the "N new folders discovered" banner on the account detail page.
     */
    fun countNewlyDiscovered(accountId: Long): Long

}
