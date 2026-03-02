package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteFSFile
import com.oconeco.spring_search_tempo.base.events.BeforeDeleteFSFolder
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
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
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime


@Service
@Transactional
class FSFileServiceImpl(
    private val fSFileRepository: FSFileRepository,
    private val fSFolderRepository: FSFolderRepository,
    private val crawlConfigRepository: CrawlConfigRepository,
    private val publisher: ApplicationEventPublisher,
    private val fSFileMapper: FSFileMapper
) : FSFileService {

    @Transactional(readOnly = true)
    override fun count(): Long = fSFileRepository.count()

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    override fun uriExists(uri: String?): Boolean = fSFileRepository.existsByUri(uri)

    @Transactional(readOnly = true)
    override fun getFSFileValues(): Map<Long, Long> = fSFileRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(FSFile::id, FSFile::id))

    @Transactional(readOnly = true)
    override fun findFilesWithBodyText(pageable: Pageable): Page<FSFileDTO> {
        val page = fSFileRepository.findByBodyTextIsNotNull(pageable)
        return PageImpl(page.content
                .map { fSFile -> fSFileMapper.updateFSFileDTO(fSFile, FSFileDTO()) },
                pageable, page.totalElements)
    }

    @Transactional(readOnly = true)
    override fun countByJobRunId(jobRunId: Long): Long =
        fSFileRepository.countByJobRunId(jobRunId)

    @Transactional(readOnly = true)
    override fun countByCrawlConfigId(crawlConfigId: Long, includeSkipped: Boolean): Long =
        if (includeSkipped) {
            fSFileRepository.countByCrawlConfigId(crawlConfigId)
        } else {
            fSFileRepository.countByCrawlConfigIdAndAnalysisStatusNot(crawlConfigId, AnalysisStatus.SKIP)
        }

    @Transactional(readOnly = true)
    override fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable, showSkipped: Boolean): Page<FSFileDTO> {
        val page = if (showSkipped) {
            fSFileRepository.findByCrawlConfigId(crawlConfigId, pageable)
        } else {
            fSFileRepository.findByCrawlConfigIdAndAnalysisStatusNot(crawlConfigId, AnalysisStatus.SKIP, pageable)
        }
        return PageImpl(
            page.content.map { fSFile -> fSFileMapper.updateFSFileDTO(fSFile, FSFileDTO()) },
            pageable,
            page.totalElements
        )
    }

    @Transactional(readOnly = true)
    override fun findFilesNeedingChunking(pageable: Pageable): Page<FSFileDTO> {
        val page = fSFileRepository.findFilesNeedingChunking(pageable)
        return PageImpl(
            page.content.map { fSFile -> fSFileMapper.updateFSFileDTO(fSFile, FSFileDTO()) },
            pageable,
            page.totalElements
        )
    }

    @Transactional(readOnly = true)
    override fun findFilesNeedingChunkingByJobRunId(jobRunId: Long, pageable: Pageable): Page<FSFileDTO> {
        val page = fSFileRepository.findFilesNeedingChunkingByJobRunId(jobRunId, pageable)
        return PageImpl(
            page.content.map { fSFile -> fSFileMapper.updateFSFileDTO(fSFile, FSFileDTO()) },
            pageable,
            page.totalElements
        )
    }

    override fun markAsChunked(fileId: Long) {
        val file = fSFileRepository.findById(fileId)
            .orElseThrow { NotFoundException("File not found: $fileId") }
        file.chunkedAt = OffsetDateTime.now()
        fSFileRepository.save(file)
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

    @Transactional(readOnly = true)
    override fun countByAnalysisStatus(): Map<String, Long> {
        return AnalysisStatus.entries.associate { status ->
            status.name to fSFileRepository.countByAnalysisStatus(status)
        }
    }

    @Transactional(readOnly = true)
    override fun countByCrawlConfigFacets(): List<Triple<Long, String, Long>> {
        val configMap = crawlConfigRepository.findAll().associateBy { it.id }
        return fSFileRepository.countGroupedByCrawlConfig(AnalysisStatus.SKIP)
            .map { row ->
                val configId = row[0] as Long
                val count = row[1] as Long
                val configName = configMap[configId]?.name ?: "Unknown"
                Triple(configId, configName, count)
            }
    }

    @Transactional(readOnly = true)
    override fun countSkippedByCrawlConfig(): Map<Long, Long> {
        return fSFileRepository.countSkippedGroupedByCrawlConfig(AnalysisStatus.SKIP)
            .associate { row ->
                val configId = row[0] as Long
                val count = row[1] as Long
                configId to count
            }
    }

    @Transactional(readOnly = true)
    override fun countByStatus(): Map<String, Long> {
        return Status.entries.associate { status ->
            status.name to fSFileRepository.countByStatus(status)
        }
    }

    @Transactional(readOnly = true)
    override fun countSearchableByCrawlConfig(): Map<Long, Long> {
        val searchableStatuses = listOf(AnalysisStatus.INDEX, AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC)
        return fSFileRepository.countSearchableGroupedByCrawlConfig(searchableStatuses)
            .associate { (it[0] as Long) to (it[1] as Long) }
    }

}
