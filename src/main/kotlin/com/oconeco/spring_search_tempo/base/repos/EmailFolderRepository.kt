package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.EmailFolder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying


interface EmailFolderRepository : JpaRepository<EmailFolder, Long> {

    fun findByEmailAccountIdAndFolderName(accountId: Long, folderName: String): EmailFolder?

    fun findByEmailAccountId(accountId: Long): List<EmailFolder>

    fun findByUri(uri: String): EmailFolder?

    fun existsByEmailAccountIdAndFolderName(accountId: Long, folderName: String): Boolean

    @Modifying
    fun deleteByEmailAccountId(accountId: Long): Int

    /**
     * Count folders for an account.
     */
    fun countByEmailAccountId(accountId: Long): Long

}
