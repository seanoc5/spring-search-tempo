package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.domain.EmailMessage
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteEmailMessage
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service


@Service
class EmailMessageServiceImpl(
    private val emailMessageRepository: EmailMessageRepository,
    private val emailAccountRepository: EmailAccountRepository,
    private val emailFolderRepository: EmailFolderRepository,
    private val publisher: ApplicationEventPublisher,
    private val emailMessageMapper: EmailMessageMapper
) : EmailMessageService {

    override fun count(): Long = emailMessageRepository.count()

    override fun findAll(pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findAll(pageable)
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun get(id: Long): EmailMessageDTO = emailMessageRepository.findById(id)
        .map { message -> emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO()) }
        .orElseThrow { NotFoundException() }

    override fun create(emailMessageDTO: EmailMessageDTO): Long {
        val emailMessage = EmailMessage()
        emailMessageMapper.updateEmailMessage(
            emailMessageDTO,
            emailMessage,
            emailAccountRepository,
            emailFolderRepository
        )
        return emailMessageRepository.save(emailMessage).id!!
    }

    override fun update(id: Long, emailMessageDTO: EmailMessageDTO) {
        val emailMessage = emailMessageRepository.findById(id)
            .orElseThrow { NotFoundException() }
        emailMessageMapper.updateEmailMessage(
            emailMessageDTO,
            emailMessage,
            emailAccountRepository,
            emailFolderRepository
        )
        emailMessageRepository.save(emailMessage)
    }

    override fun delete(id: Long) {
        val emailMessage = emailMessageRepository.findById(id)
            .orElseThrow { NotFoundException() }
        publisher.publishEvent(BeforeDeleteEmailMessage(id))
        emailMessageRepository.delete(emailMessage)
    }

    override fun existsByMessageId(messageId: String): Boolean =
        emailMessageRepository.existsByMessageId(messageId)

    override fun getEmailMessageValues(): Map<Long, Long> =
        emailMessageRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(EmailMessage::id, EmailMessage::id))

    override fun findMessagesWithBodyText(pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findByBodyTextIsNotNull(pageable)
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun findMessagesWithBodyTextByAccount(accountId: Long, pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findByBodyTextIsNotNullAndEmailAccountId(accountId, pageable)
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun countByAccount(accountId: Long): Long =
        emailMessageRepository.countByEmailAccountId(accountId)

}
