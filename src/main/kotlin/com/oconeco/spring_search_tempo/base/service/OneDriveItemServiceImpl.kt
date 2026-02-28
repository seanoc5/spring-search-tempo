package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.OneDriveItemService
import com.oconeco.spring_search_tempo.base.domain.OneDriveFetchStatus
import com.oconeco.spring_search_tempo.base.domain.OneDriveItem
import com.oconeco.spring_search_tempo.base.model.OneDriveItemDTO
import com.oconeco.spring_search_tempo.base.repos.OneDriveAccountRepository
import com.oconeco.spring_search_tempo.base.repos.OneDriveItemRepository
import com.oconeco.spring_search_tempo.base.util.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime


@Service
@Transactional
class OneDriveItemServiceImpl(
    private val oneDriveItemRepository: OneDriveItemRepository,
    private val oneDriveAccountRepository: OneDriveAccountRepository,
    private val oneDriveItemMapper: OneDriveItemMapper
) : OneDriveItemService {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveItemServiceImpl::class.java)
    }

    @Transactional(readOnly = true)
    override fun count(): Long = oneDriveItemRepository.count()

    @Transactional(readOnly = true)
    override fun get(id: Long): OneDriveItemDTO = oneDriveItemRepository.findById(id)
        .map { item -> oneDriveItemMapper.updateOneDriveItemDTO(item, OneDriveItemDTO()) }
        .orElseThrow { NotFoundException() }

    override fun create(oneDriveItemDTO: OneDriveItemDTO): Long {
        val item = OneDriveItem()
        oneDriveItemMapper.updateOneDriveItem(oneDriveItemDTO, item)
        if (oneDriveItemDTO.oneDriveAccount != null) {
            item.oneDriveAccount = oneDriveAccountRepository.findById(oneDriveItemDTO.oneDriveAccount!!)
                .orElseThrow { NotFoundException("OneDrive account not found") }
        }
        return oneDriveItemRepository.save(item).id!!
    }

    override fun update(id: Long, oneDriveItemDTO: OneDriveItemDTO) {
        val item = oneDriveItemRepository.findById(id)
            .orElseThrow { NotFoundException() }
        oneDriveItemMapper.updateOneDriveItem(oneDriveItemDTO, item)
        oneDriveItemRepository.save(item)
    }

    override fun delete(id: Long) {
        val item = oneDriveItemRepository.findById(id)
            .orElseThrow { NotFoundException() }
        oneDriveItemRepository.delete(item)
    }

    override fun upsertFromGraphItem(dto: OneDriveItemDTO): Long {
        val graphItemId = dto.graphItemId ?: throw IllegalArgumentException("graphItemId is required")
        val driveId = dto.driveId ?: throw IllegalArgumentException("driveId is required")

        val existing = oneDriveItemRepository.findByGraphItemIdAndDriveId(graphItemId, driveId)

        if (existing != null) {
            // Update existing item
            oneDriveItemMapper.updateOneDriveItem(dto, existing)
            return oneDriveItemRepository.save(existing).id!!
        }

        // Create new item
        val item = OneDriveItem()
        oneDriveItemMapper.updateOneDriveItem(dto, item)
        if (dto.oneDriveAccount != null) {
            item.oneDriveAccount = oneDriveAccountRepository.findById(dto.oneDriveAccount!!)
                .orElseThrow { NotFoundException("OneDrive account not found") }
        }
        return oneDriveItemRepository.save(item).id!!
    }

    override fun markAsDeleted(graphItemId: String, driveId: String) {
        val updated = oneDriveItemRepository.markAsDeleted(graphItemId, driveId)
        if (updated > 0) {
            log.debug("Marked item as deleted: graphItemId={}, driveId={}", graphItemId, driveId)
        }
    }

    @Transactional(readOnly = true)
    override fun findMetadataOnlyForDownload(accountId: Long, pageable: Pageable): Page<OneDriveItemDTO> {
        return oneDriveItemRepository.findMetadataOnlyForDownload(accountId, pageable)
            .map { item -> oneDriveItemMapper.updateOneDriveItemDTO(item, OneDriveItemDTO()) }
    }

    @Transactional(readOnly = true)
    override fun countMetadataOnlyForDownload(accountId: Long): Long {
        return oneDriveItemRepository.countMetadataOnlyForDownload(accountId)
    }

    override fun updateContentAndComplete(
        id: Long,
        bodyText: String?,
        bodySize: Long?,
        contentType: String?,
        author: String?,
        title: String?,
        pageCount: Int?
    ) {
        val item = oneDriveItemRepository.findById(id)
            .orElseThrow { NotFoundException() }
        item.bodyText = bodyText
        item.bodySize = bodySize
        item.contentType = contentType
        item.author = author
        item.title = title
        item.pageCount = pageCount
        item.fetchStatus = OneDriveFetchStatus.COMPLETE
        oneDriveItemRepository.save(item)
    }

    override fun markDownloadFailed(id: Long, error: String?) {
        val item = oneDriveItemRepository.findById(id)
            .orElseThrow { NotFoundException() }
        item.fetchStatus = OneDriveFetchStatus.DOWNLOAD_FAILED
        item.description = error?.take(2000)
        oneDriveItemRepository.save(item)
    }

    @Transactional(readOnly = true)
    override fun findUnchunkedByAccount(accountId: Long, pageable: Pageable): Page<OneDriveItemDTO> {
        return oneDriveItemRepository
            .findByOneDriveAccountIdAndBodyTextIsNotNullAndChunkedAtIsNullAndIsFolderFalse(accountId, pageable)
            .map { item -> oneDriveItemMapper.updateOneDriveItemDTO(item, OneDriveItemDTO()) }
    }

    @Transactional(readOnly = true)
    override fun countUnchunkedByAccount(accountId: Long): Long {
        return oneDriveItemRepository.countNeedingChunking(accountId)
    }

    override fun markAsChunked(id: Long) {
        val item = oneDriveItemRepository.findById(id)
            .orElseThrow { NotFoundException() }
        item.chunkedAt = OffsetDateTime.now()
        oneDriveItemRepository.save(item)
    }

    @Transactional(readOnly = true)
    override fun countByAccount(accountId: Long): Long {
        return oneDriveItemRepository.countByOneDriveAccountId(accountId)
    }

}
