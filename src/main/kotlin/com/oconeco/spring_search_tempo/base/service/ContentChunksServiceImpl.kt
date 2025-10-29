package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.ContentChunksService
import com.oconeco.spring_search_tempo.base.domain.ContentChunks
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteContentChunks
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteFSFile
import com.oconeco.spring_search_tempo.base.model.ContentChunksDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunksRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.oconeco.spring_search_tempo.base.util.ReferencedException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service


@Service
class ContentChunksServiceImpl(
    private val contentChunksRepository: ContentChunksRepository,
    private val fSFileRepository: FSFileRepository,
    private val publisher: ApplicationEventPublisher,
    private val contentChunksMapper: ContentChunksMapper
) : ContentChunksService {

    override fun findAll(): List<ContentChunksDTO> {
        val contentChunkses = contentChunksRepository.findAll(Sort.by("id"))
        return contentChunkses.map { contentChunks ->
                contentChunksMapper.updateContentChunksDTO(contentChunks, ContentChunksDTO()) }
    }

    override fun `get`(id: Long): ContentChunksDTO = contentChunksRepository.findById(id)
            .map { contentChunks -> contentChunksMapper.updateContentChunksDTO(contentChunks,
            ContentChunksDTO()) }
            .orElseThrow { NotFoundException() }

    override fun create(contentChunksDTO: ContentChunksDTO): Long {
        val contentChunks = ContentChunks()
        contentChunksMapper.updateContentChunks(contentChunksDTO, contentChunks,
                contentChunksRepository, fSFileRepository)
        return contentChunksRepository.save(contentChunks).id!!
    }

    override fun update(id: Long, contentChunksDTO: ContentChunksDTO) {
        val contentChunks = contentChunksRepository.findById(id)
                .orElseThrow { NotFoundException() }
        contentChunksMapper.updateContentChunks(contentChunksDTO, contentChunks,
                contentChunksRepository, fSFileRepository)
        contentChunksRepository.save(contentChunks)
    }

    override fun delete(id: Long) {
        val contentChunks = contentChunksRepository.findById(id)
                .orElseThrow { NotFoundException() }
        publisher.publishEvent(BeforeDeleteContentChunks(id))
        contentChunksRepository.delete(contentChunks)
    }

    override fun getContentChunksValues(): Map<Long, Long> =
            contentChunksRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(ContentChunks::id, ContentChunks::id))

    @EventListener(BeforeDeleteContentChunks::class)
    fun on(event: BeforeDeleteContentChunks) {
        val referencedException = ReferencedException()
        val parentChunkContentChunks =
                contentChunksRepository.findFirstByParentChunkIdAndIdNot(event.id, event.id)
        if (parentChunkContentChunks != null) {
            referencedException.key = "contentChunks.contentChunks.parentChunk.referenced"
            referencedException.addParam(parentChunkContentChunks.id)
            throw referencedException
        }
    }

    @EventListener(BeforeDeleteFSFile::class)
    fun on(event: BeforeDeleteFSFile) {
        val referencedException = ReferencedException()
        val conceptContentChunks = contentChunksRepository.findFirstByConceptId(event.id)
        if (conceptContentChunks != null) {
            referencedException.key = "fSFile.contentChunks.concept.referenced"
            referencedException.addParam(conceptContentChunks.id)
            throw referencedException
        }
    }

}
