package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteContentChunk
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteEmailMessage
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteFSFile
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.EmailMessageRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.oconeco.spring_search_tempo.base.util.ReferencedException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
@Transactional
class ContentChunkServiceImpl(
    private val contentChunkRepository: ContentChunkRepository,
    private val fSFileRepository: FSFileRepository,
    private val emailMessageRepository: EmailMessageRepository,
    private val publisher: ApplicationEventPublisher,
    private val contentChunkMapper: ContentChunkMapper
) : ContentChunkService {

    @Transactional(readOnly = true)
    override fun count(): Long = contentChunkRepository.count()

    @Transactional(readOnly = true)
    override fun findAll(): List<ContentChunkDTO> {
        val contentChunks = contentChunkRepository.findAll(Sort.by("id"))
        return contentChunks.map { contentChunk ->
                contentChunkMapper.updateContentChunkDTO(contentChunk, ContentChunkDTO()) }
    }

    @Transactional(readOnly = true)
    override fun `get`(id: Long): ContentChunkDTO = contentChunkRepository.findById(id)
            .map { contentChunk -> contentChunkMapper.updateContentChunkDTO(contentChunk,
            ContentChunkDTO()) }
            .orElseThrow { NotFoundException() }

    override fun create(contentChunkDTO: ContentChunkDTO): Long {
        val contentChunk = ContentChunk()
        contentChunkMapper.updateContentChunk(contentChunkDTO, contentChunk,
                contentChunkRepository, fSFileRepository, emailMessageRepository)
        return contentChunkRepository.save(contentChunk).id!!
    }

    override fun update(id: Long, contentChunkDTO: ContentChunkDTO) {
        val contentChunk = contentChunkRepository.findById(id)
                .orElseThrow { NotFoundException() }
        contentChunkMapper.updateContentChunk(contentChunkDTO, contentChunk,
                contentChunkRepository, fSFileRepository, emailMessageRepository)
        contentChunkRepository.save(contentChunk)
    }

    override fun delete(id: Long) {
        val contentChunk = contentChunkRepository.findById(id)
                .orElseThrow { NotFoundException() }
        publisher.publishEvent(BeforeDeleteContentChunk(id))
        contentChunkRepository.delete(contentChunk)
    }

    @Transactional(readOnly = true)
    override fun getContentChunkValues(): Map<Long, Long> =
            contentChunkRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(ContentChunk::id, ContentChunk::id))

    @EventListener(BeforeDeleteContentChunk::class)
    fun on(event: BeforeDeleteContentChunk) {
        val referencedException = ReferencedException()
        val parentChunkContentChunk =
                contentChunkRepository.findFirstByParentChunkIdAndIdNot(event.id, event.id)
        if (parentChunkContentChunk != null) {
            referencedException.key = "contentChunk.contentChunk.parentChunk.referenced"
            referencedException.addParam(parentChunkContentChunk.id)
            throw referencedException
        }
    }

    @EventListener(BeforeDeleteFSFile::class)
    fun on(event: BeforeDeleteFSFile) {
        val referencedException = ReferencedException()
        val conceptContentChunk = contentChunkRepository.findFirstByConceptId(event.id)
        if (conceptContentChunk != null) {
            referencedException.key = "fSFile.contentChunk.concept.referenced"
            referencedException.addParam(conceptContentChunk.id)
            throw referencedException
        }
    }

    @EventListener(BeforeDeleteEmailMessage::class)
    fun on(event: BeforeDeleteEmailMessage) {
        val referencedException = ReferencedException()
        val emailMessageContentChunk = contentChunkRepository.findFirstByEmailMessageId(event.id)
        if (emailMessageContentChunk != null) {
            referencedException.key = "emailMessage.contentChunk.emailMessage.referenced"
            referencedException.addParam(emailMessageContentChunk.id)
            throw referencedException
        }
    }

}
