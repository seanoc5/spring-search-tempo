package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteFSFolder
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service


@Service
class FSFolderServiceImpl(
    private val fSFolderRepository: FSFolderRepository,
    private val publisher: ApplicationEventPublisher,
    private val fSFolderMapper: FSFolderMapper
) : FSFolderService {

    override fun findAll(filter: String?, pageable: Pageable): Page<FSFolderDTO> {
        var page: Page<FSFolder>
        if (filter != null) {
            page = fSFolderRepository.findAllById(filter.toLongOrNull(), pageable)
        } else {
            page = fSFolderRepository.findAll(pageable)
        }
        return PageImpl(page.content
                .map { fSFolder -> fSFolderMapper.updateFSFolderDTO(fSFolder, FSFolderDTO()) },
                pageable, page.totalElements)
    }

    override fun `get`(id: Long): FSFolderDTO = fSFolderRepository.findById(id)
            .map { fSFolder -> fSFolderMapper.updateFSFolderDTO(fSFolder, FSFolderDTO()) }
            .orElseThrow { NotFoundException() }

    override fun create(fSFolderDTO: FSFolderDTO): Long {
        val fSFolder = FSFolder()
        fSFolderMapper.updateFSFolder(fSFolderDTO, fSFolder)
        return fSFolderRepository.save(fSFolder).id!!
    }

    override fun update(id: Long, fSFolderDTO: FSFolderDTO) {
        val fSFolder = fSFolderRepository.findById(id)
                .orElseThrow { NotFoundException() }
        fSFolderMapper.updateFSFolder(fSFolderDTO, fSFolder)
        fSFolderRepository.save(fSFolder)
    }

    override fun delete(id: Long) {
        val fSFolder = fSFolderRepository.findById(id)
                .orElseThrow { NotFoundException() }
        publisher.publishEvent(BeforeDeleteFSFolder(id))
        fSFolderRepository.delete(fSFolder)
    }

    override fun uriExists(uri: String?): Boolean = fSFolderRepository.existsByUri(uri)

    override fun getFSFolderValues(): Map<Long, Long> = fSFolderRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(FSFolder::id, FSFolder::id))

}
