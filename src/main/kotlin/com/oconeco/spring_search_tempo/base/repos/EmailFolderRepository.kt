package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.EmailFolder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying


interface EmailFolderRepository : JpaRepository<EmailFolder, Long> {

    fun findByEmailAccountIdAndFolderName(accountId: Long, folderName: String): EmailFolder?

    fun findByEmailAccountIdAndPath(accountId: Long, path: String): EmailFolder?

    fun findByEmailAccountId(accountId: Long): List<EmailFolder>

    /** Folders flagged as sync targets and not `\Noselect` (i.e. actually openable). */
    fun findByEmailAccountIdAndSyncEnabledTrueAndNoselectFalse(accountId: Long): List<EmailFolder>

    fun findByUri(uri: String): EmailFolder?

    fun existsByEmailAccountIdAndFolderName(accountId: Long, folderName: String): Boolean

    @Modifying
    fun deleteByEmailAccountId(accountId: Long): Int

    /**
     * Count folders for an account.
     */
    fun countByEmailAccountId(accountId: Long): Long

    /**
     * Issue #84: folders observed during the most-recent re-enumeration that
     * the operator hasn't opted into yet. Newly-discovered folders land with
     * `syncEnabled=false` and `lastSyncUid IS NULL` (never synced); the
     * "N new folders discovered" banner on the account detail page uses this
     * count to invite the operator to the folder list.
     *
     * \Noselect folders also satisfy `syncEnabled=false`, so we exclude them
     * — they're not actionable.
     */
    fun countByEmailAccountIdAndSyncEnabledFalseAndNoselectFalseAndLastSyncUidIsNull(
        accountId: Long
    ): Long

}
