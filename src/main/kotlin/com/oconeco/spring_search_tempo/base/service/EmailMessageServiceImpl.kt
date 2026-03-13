package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailMessageService
import com.oconeco.spring_search_tempo.base.domain.EmailCategory
import com.oconeco.spring_search_tempo.base.domain.EmailMessage
import com.oconeco.spring_search_tempo.base.domain.FetchStatus
import java.time.OffsetDateTime
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.repos.EmailAccountRepository
import com.oconeco.spring_search_tempo.base.repos.EmailFolderRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class EmailMessageServiceImpl(
    private val emailMessageRepository: EmailMessageRepository,
    private val emailAccountRepository: EmailAccountRepository,
    private val emailFolderRepository: EmailFolderRepository,
    private val smartDeleteService: SmartDeleteService,
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

    override fun createBulk(dtos: List<EmailMessageDTO>): List<Long> {
        if (dtos.isEmpty()) return emptyList()

        val entities = dtos.map { dto ->
            val entity = EmailMessage()
            emailMessageMapper.updateEmailMessage(
                dto,
                entity,
                emailAccountRepository,
                emailFolderRepository
            )
            entity
        }
        return emailMessageRepository.saveAll(entities).mapNotNull { it.id }
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
        smartDeleteService.deleteEmailMessage(id)
    }

    override fun existsByMessageId(messageId: String): Boolean =
        emailMessageRepository.existsByMessageId(messageId)

    override fun existsByUri(uri: String): Boolean =
        emailMessageRepository.findByUri(uri) != null

    override fun findExistingMessageIds(messageIds: Collection<String>): Set<String> =
        if (messageIds.isEmpty()) emptySet()
        else emailMessageRepository.findExistingMessageIds(messageIds).toSet()

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

    override fun findHeadersOnlyByAccount(accountId: Long, pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findByEmailAccountIdAndFetchStatus(
            accountId,
            FetchStatus.HEADERS_ONLY,
            pageable
        )
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun countHeadersOnlyByAccount(accountId: Long): Long =
        emailMessageRepository.countByEmailAccountIdAndFetchStatus(accountId, FetchStatus.HEADERS_ONLY)

    override fun search(filter: String, pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.search(filter, pageable)
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    @Transactional
    override fun updateBodyAndComplete(
        id: Long,
        bodyText: String?,
        bodySize: Long?,
        hasAttachments: Boolean,
        attachmentCount: Int,
        attachmentNames: String?
    ) {
        // PERFORMANCE: Direct UPDATE query avoids SELECT+UPDATE pattern
        emailMessageRepository.updateBodyDirect(
            id = id,
            bodyText = bodyText,
            bodySize = bodySize,
            hasAttachments = hasAttachments,
            attachmentCount = attachmentCount,
            attachmentNames = attachmentNames
        )
    }

    override fun findUncategorizedByAccount(accountId: Long, pageable: Pageable): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findByEmailAccountIdAndFetchStatusAndCategorizedAtIsNull(
            accountId,
            FetchStatus.COMPLETE,
            pageable
        )
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun countUncategorizedByAccount(accountId: Long): Long =
        emailMessageRepository.countByEmailAccountIdAndFetchStatusAndCategorizedAtIsNull(
            accountId,
            FetchStatus.COMPLETE
        )

    @Transactional
    override fun updateCategorization(
        id: Long,
        category: EmailCategory,
        confidence: Double?,
        categorizedAt: OffsetDateTime?
    ) {
        emailMessageRepository.updateCategorization(id, category, confidence, categorizedAt)
    }

    @Transactional
    override fun markAsRead(id: Long) {
        emailMessageRepository.updateReadStatus(id, true)
    }

    @Transactional
    override fun markAsUnread(id: Long) {
        emailMessageRepository.updateReadStatus(id, false)
    }

    @Transactional
    override fun toggleReadStatus(id: Long): Boolean {
        val message = emailMessageRepository.findById(id)
            .orElseThrow { NotFoundException() }
        val newStatus = !message.isRead
        emailMessageRepository.updateReadStatus(id, newStatus)
        return newStatus
    }

    override fun countUnreadByAccount(accountId: Long): Long =
        emailMessageRepository.countByEmailAccountIdAndIsRead(accountId, false)

    override fun findInterestingForChunking(
        accountId: Long,
        cutoffDate: OffsetDateTime,
        forceRefresh: Boolean,
        pageable: Pageable
    ): Page<EmailMessageDTO> {
        val page = emailMessageRepository.findInterestingForChunking(
            accountId, cutoffDate, forceRefresh, pageable
        )
        return PageImpl(
            page.content.map { message ->
                emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
            },
            pageable,
            page.totalElements
        )
    }

    override fun getWithTags(id: Long): EmailMessageDTO {
        val message = emailMessageRepository.findByIdWithTags(id)
            ?: throw NotFoundException()
        val dto = emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
        dto.tagIds = message.tags.mapNotNull { it.id }.toMutableList()
        return dto
    }

}
