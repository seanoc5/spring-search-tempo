package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface EmailMessageService {

    fun count(): Long

    fun findAll(pageable: Pageable): Page<EmailMessageDTO>

    fun get(id: Long): EmailMessageDTO

    fun create(emailMessageDTO: EmailMessageDTO): Long

    fun update(id: Long, emailMessageDTO: EmailMessageDTO)

    fun delete(id: Long)

    fun existsByMessageId(messageId: String): Boolean

    fun getEmailMessageValues(): Map<Long, Long>

    /**
     * Find messages with non-null bodyText for chunking.
     * Used by batch processing to retrieve messages that need text chunking.
     */
    fun findMessagesWithBodyText(pageable: Pageable): Page<EmailMessageDTO>

    /**
     * Find messages with bodyText for a specific account.
     */
    fun findMessagesWithBodyTextByAccount(accountId: Long, pageable: Pageable): Page<EmailMessageDTO>

    /**
     * Count messages for a specific account.
     */
    fun countByAccount(accountId: Long): Long

}
