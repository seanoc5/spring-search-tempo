package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.EmailTagService
import com.oconeco.spring_search_tempo.base.domain.EmailTag
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import com.oconeco.spring_search_tempo.base.model.EmailTagDTO
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import com.oconeco.spring_search_tempo.base.repos.EmailTagRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class EmailTagServiceImpl(
    private val emailTagRepository: EmailTagRepository,
    private val emailMessageRepository: EmailMessageRepository,
    private val emailTagMapper: EmailTagMapper,
    private val emailMessageMapper: EmailMessageMapper
) : EmailTagService {

    companion object {
        private val log = LoggerFactory.getLogger(EmailTagServiceImpl::class.java)
        private const val SYSTEM_TAG_JUNK = "junk"
    }

    override fun count(): Long = emailTagRepository.count()

    override fun findAll(): List<EmailTagDTO> {
        return emailTagRepository.findAll(Sort.by("name")).map { tag ->
            emailTagMapper.updateEmailTagDTO(tag, EmailTagDTO())
        }
    }

    override fun findAllWithCounts(): List<EmailTagDTO> {
        return emailTagRepository.findAllWithMessageCounts().map { row ->
            val tag = row[0] as EmailTag
            val count = row[1] as Long
            val dto = emailTagMapper.updateEmailTagDTO(tag, EmailTagDTO())
            dto.messageCount = count
            dto
        }
    }

    override fun get(id: Long): EmailTagDTO {
        val tag = emailTagRepository.findById(id)
            .orElseThrow { NotFoundException() }
        return emailTagMapper.updateEmailTagDTO(tag, EmailTagDTO())
    }

    override fun findByName(name: String): EmailTagDTO? {
        return emailTagRepository.findByName(name)?.let { tag ->
            emailTagMapper.updateEmailTagDTO(tag, EmailTagDTO())
        }
    }

    override fun create(emailTagDTO: EmailTagDTO): Long {
        val name = emailTagDTO.name?.lowercase()?.trim()
        if (name == SYSTEM_TAG_JUNK) {
            throw IllegalArgumentException("Cannot create tag with reserved name '$SYSTEM_TAG_JUNK'")
        }

        if (emailTagRepository.existsByName(emailTagDTO.name!!)) {
            throw IllegalArgumentException("Tag with name '${emailTagDTO.name}' already exists")
        }

        val tag = EmailTag()
        emailTagMapper.updateEmailTag(emailTagDTO, tag)
        tag.isSystem = false  // Ensure user-created tags are never system tags

        log.info("Creating email tag: {}", tag.name)
        return emailTagRepository.save(tag).id!!
    }

    override fun update(id: Long, emailTagDTO: EmailTagDTO) {
        val tag = emailTagRepository.findById(id)
            .orElseThrow { NotFoundException() }

        if (tag.isSystem) {
            throw IllegalArgumentException("Cannot modify system tag '${tag.name}'")
        }

        val newName = emailTagDTO.name?.lowercase()?.trim()
        if (newName == SYSTEM_TAG_JUNK) {
            throw IllegalArgumentException("Cannot rename tag to reserved name '$SYSTEM_TAG_JUNK'")
        }

        // Check for duplicate name (only if name changed)
        if (tag.name != emailTagDTO.name && emailTagRepository.existsByName(emailTagDTO.name!!)) {
            throw IllegalArgumentException("Tag with name '${emailTagDTO.name}' already exists")
        }

        emailTagMapper.updateEmailTag(emailTagDTO, tag)
        emailTagRepository.save(tag)
        log.info("Updated email tag: {}", tag.name)
    }

    @Transactional
    override fun delete(id: Long) {
        val tag = emailTagRepository.findById(id)
            .orElseThrow { NotFoundException() }

        if (tag.isSystem) {
            throw IllegalArgumentException("Cannot delete system tag '${tag.name}'")
        }

        log.info("Deleting email tag: {}", tag.name)
        emailTagRepository.delete(tag)
    }

    @Transactional
    override fun addTagToMessage(messageId: Long, tagId: Long) {
        val message = emailMessageRepository.findByIdWithTags(messageId)
            ?: throw NotFoundException("EmailMessage not found")
        val tag = emailTagRepository.findById(tagId)
            .orElseThrow { NotFoundException("EmailTag not found") }

        message.tags.add(tag)
        emailMessageRepository.save(message)
        log.debug("Added tag '{}' to message {}", tag.name, messageId)
    }

    @Transactional
    override fun removeTagFromMessage(messageId: Long, tagId: Long) {
        val message = emailMessageRepository.findByIdWithTags(messageId)
            ?: throw NotFoundException("EmailMessage not found")
        val tag = emailTagRepository.findById(tagId)
            .orElseThrow { NotFoundException("EmailTag not found") }

        message.tags.remove(tag)
        emailMessageRepository.save(message)
        log.debug("Removed tag '{}' from message {}", tag.name, messageId)
    }

    override fun findMessagesWithTag(tagId: Long, pageable: Pageable): Page<EmailMessageDTO> {
        return emailMessageRepository.findByTagId(tagId, pageable).map { message ->
            emailMessageMapper.updateEmailMessageDTO(message, EmailMessageDTO())
        }
    }

    override fun countMessagesWithTag(tagId: Long): Long {
        return emailMessageRepository.countByTagId(tagId)
    }

    override fun nameExists(name: String): Boolean {
        return emailTagRepository.existsByName(name)
    }

}
