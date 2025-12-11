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
    fun findOrCreate(accountId: Long, folderName: String, fullPath: String): EmailFolderDTO

    /**
     * Update sync state after processing a folder.
     */
    fun updateSyncState(id: Long, lastUid: Long, messageCount: Long)

}
