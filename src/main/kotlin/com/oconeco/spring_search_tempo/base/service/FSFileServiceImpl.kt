package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteFSFile
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteFSFolder
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import com.oconeco.spring_search_tempo.base.util.ReferencedException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service


@Service
class FSFileServiceImpl(
    private val fSFileRepository: FSFileRepository,
    private val fSFolderRepository: FSFolderRepository,
    private val publisher: ApplicationEventPublisher,
    private val fSFileMapper: FSFileMapper
) : FSFileService {

    override fun findAll(filter: String?, pageable: Pageable, showSkipped: Boolean): Page<FSFileDTO> {
        var page: Page<FSFile>
        if (filter != null) {
            val filterId = filter.toLongOrNull()
            page = if (showSkipped) {
                fSFileRepository.findAllById(filterId, pageable)
            } else {
                fSFileRepository.findByIdAndAnalysisStatusNot(filterId ?: 0L, AnalysisStatus.SKIP, pageable)
            }
        } else {
            page = if (showSkipped) {
                fSFileRepository.findAll(pageable)
            } else {
                fSFileRepository.findByAnalysisStatusNot(AnalysisStatus.SKIP, pageable)
            }
        }
        return PageImpl(page.content
                .map { fSFile -> fSFileMapper.updateFSFileDTO(fSFile, FSFileDTO()) },
                pageable, page.totalElements)
    }

    override fun `get`(id: Long): FSFileDTO = fSFileRepository.findById(id)
            .map { fSFile -> fSFileMapper.updateFSFileDTO(fSFile, FSFileDTO()) }
            .orElseThrow { NotFoundException() }

    override fun create(fSFileDTO: FSFileDTO): Long {
        val fSFile = FSFile()
        fSFileMapper.updateFSFile(fSFileDTO, fSFile, fSFolderRepository)
        return fSFileRepository.save(fSFile).id!!
    }

    override fun update(id: Long, fSFileDTO: FSFileDTO) {
        val fSFile = fSFileRepository.findById(id)
                .orElseThrow { NotFoundException() }
        fSFileMapper.updateFSFile(fSFileDTO, fSFile, fSFolderRepository)
        fSFileRepository.save(fSFile)
    }

    override fun delete(id: Long) {
        val fSFile = fSFileRepository.findById(id)
                .orElseThrow { NotFoundException() }
        publisher.publishEvent(BeforeDeleteFSFile(id))
        fSFileRepository.delete(fSFile)
    }

    override fun uriExists(uri: String?): Boolean = fSFileRepository.existsByUri(uri)

    override fun getFSFileValues(): Map<Long, Long> = fSFileRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(FSFile::id, FSFile::id))

    override fun findFilesWithBodyText(pageable: Pageable): Page<FSFileDTO> {
        val page = fSFileRepository.findByBodyTextIsNotNull(pageable)
        return PageImpl(page.content
                .map { fSFile -> fSFileMapper.updateFSFileDTO(fSFile, FSFileDTO()) },
                pageable, page.totalElements)
    }

    @EventListener(BeforeDeleteFSFolder::class)
    fun on(event: BeforeDeleteFSFolder) {
        val referencedException = ReferencedException()
        val fsFolderFSFile = fSFileRepository.findFirstByFsFolderId(event.id)
        if (fsFolderFSFile != null) {
            referencedException.key = "fSFolder.fSFile.fsFolder.referenced"
            referencedException.addParam(fsFolderFSFile.id)
            throw referencedException
        }
    }

}
