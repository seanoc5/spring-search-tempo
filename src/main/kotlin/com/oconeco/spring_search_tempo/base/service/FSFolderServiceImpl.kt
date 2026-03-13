package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import com.oconeco.spring_search_tempo.base.repos.CrawlConfigRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.util.CustomCollectors
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
@Transactional
class FSFolderServiceImpl(
    private val fSFolderRepository: FSFolderRepository,
    private val crawlConfigRepository: CrawlConfigRepository,
    private val smartDeleteService: SmartDeleteService,
    @Qualifier("FSFolderMapperImpl") private val fSFolderMapper: FSFolderMapper
) : FSFolderService {

    @Transactional(readOnly = true)
    override fun count(): Long = fSFolderRepository.count()

    @Transactional(readOnly = true)
    override fun findAll(filter: String?, pageable: Pageable, showSkipped: Boolean): Page<FSFolderDTO> {
        if (filter != null) {
            // Filter mode: support numeric ID or URI substring.
            val trimmedFilter = filter.trim()
            val filterId = trimmedFilter.toLongOrNull()
            val page = if (filterId != null) {
                if (showSkipped) {
                    fSFolderRepository.findAllById(filterId, pageable)
                } else {
                    fSFolderRepository.findByIdAndAnalysisStatusNot(filterId, AnalysisStatus.SKIP, pageable)
                }
            } else {
                if (showSkipped) {
                    fSFolderRepository.findByUriContainingIgnoreCase(trimmedFilter, pageable)
                } else {
                    fSFolderRepository.findByUriContainingIgnoreCaseAndAnalysisStatusNot(
                        trimmedFilter,
                        AnalysisStatus.SKIP,
                        pageable
                    )
                }
            }
            return PageImpl(
                page.content.map { fSFolder -> fSFolderMapper.updateFSFolderDTO(fSFolder, FSFolderDTO()) },
                pageable,
                page.totalElements
            )
        }

        // Main list: use queries with JobRun label via left join
        val page = if (showSkipped) {
            fSFolderRepository.findAllWithJobRunLabel(pageable)
        } else {
            fSFolderRepository.findByAnalysisStatusNotWithJobRunLabel(AnalysisStatus.SKIP, pageable)
        }
        return PageImpl(
            page.content.map { row ->
                val fSFolder = row[0] as FSFolder
                val jobRunLabel = row[1] as String?
                fSFolderMapper.updateFSFolderDTO(fSFolder, FSFolderDTO()).apply {
                    this.jobRunLabel = jobRunLabel
                }
            },
            pageable,
            page.totalElements
        )
    }

    @Transactional(readOnly = true)
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
        smartDeleteService.deleteFSFolder(id)
    }

    @Transactional(readOnly = true)
    override fun uriExists(uri: String?): Boolean = fSFolderRepository.existsByUri(uri)

    @Transactional(readOnly = true)
    override fun getFSFolderValues(): Map<Long, Long> = fSFolderRepository.findAll(Sort.by("id"))
            .stream()
            .collect(CustomCollectors.toSortedMap(FSFolder::id, FSFolder::id))

    @Transactional(readOnly = true)
    override fun countByJobRunId(jobRunId: Long): Long =
        fSFolderRepository.countByJobRunId(jobRunId)

    @Transactional(readOnly = true)
    override fun countByCrawlConfigId(crawlConfigId: Long, includeSkipped: Boolean): Long =
        if (includeSkipped) {
            fSFolderRepository.countByCrawlConfigId(crawlConfigId)
        } else {
            fSFolderRepository.countByCrawlConfigIdAndAnalysisStatusNot(crawlConfigId, AnalysisStatus.SKIP)
        }

    @Transactional(readOnly = true)
    override fun findByCrawlConfigId(crawlConfigId: Long, pageable: Pageable, showSkipped: Boolean): Page<FSFolderDTO> {
        val page = if (showSkipped) {
            fSFolderRepository.findByCrawlConfigId(crawlConfigId, pageable)
        } else {
            fSFolderRepository.findByCrawlConfigIdAndAnalysisStatusNot(crawlConfigId, AnalysisStatus.SKIP, pageable)
        }
        return PageImpl(
            page.content.map { fSFolder -> fSFolderMapper.updateFSFolderDTO(fSFolder, FSFolderDTO()) },
            pageable,
            page.totalElements
        )
    }

    @Transactional(readOnly = true)
    override fun countByCrawlConfigFacets(): List<Triple<Long, String, Long>> {
        val configMap = crawlConfigRepository.findAll().associateBy { it.id }
        return fSFolderRepository.countGroupedByCrawlConfig(AnalysisStatus.SKIP)
            .map { row ->
                val configId = row[0] as Long
                val count = row[1] as Long
                val configName = configMap[configId]?.name ?: "Unknown"
                Triple(configId, configName, count)
            }
    }

    @Transactional(readOnly = true)
    override fun countSkippedByCrawlConfig(): Map<Long, Long> {
        return fSFolderRepository.countSkippedGroupedByCrawlConfig(AnalysisStatus.SKIP)
            .associate { row ->
                val configId = row[0] as Long
                val count = row[1] as Long
                configId to count
            }
    }

    @Transactional(readOnly = true)
    override fun countByStatus(): Map<String, Long> {
        return Status.entries.associate { status ->
            status.name to fSFolderRepository.countByStatus(status)
        }
    }

    @Transactional(readOnly = true)
    override fun countByAnalysisStatus(): Map<String, Long> {
        return AnalysisStatus.entries.associate { status ->
            status.name to fSFolderRepository.countByAnalysisStatus(status)
        }
    }

}
