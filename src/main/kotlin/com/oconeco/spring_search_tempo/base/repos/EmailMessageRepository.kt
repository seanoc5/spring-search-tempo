package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.EmailMessage
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository


interface EmailMessageRepository : JpaRepository<EmailMessage, Long> {

    fun findByMessageId(messageId: String): EmailMessage?

    fun findByUri(uri: String): EmailMessage?

    fun existsByMessageId(messageId: String): Boolean

    fun findByBodyTextIsNotNull(pageable: Pageable): Page<EmailMessage>

    fun findByBodyTextIsNotNullAndEmailAccountId(accountId: Long, pageable: Pageable): Page<EmailMessage>

    fun findByEmailAccountIdAndImapUidGreaterThan(accountId: Long, uid: Long): List<EmailMessage>

    fun findByEmailFolderId(folderId: Long): List<EmailMessage>

    fun countByEmailAccountId(accountId: Long): Long

    fun countByEmailFolderId(folderId: Long): Long

}
