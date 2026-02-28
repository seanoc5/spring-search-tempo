package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.OneDriveItemDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface OneDriveItemService {

    fun count(): Long

    fun get(id: Long): OneDriveItemDTO

    fun create(oneDriveItemDTO: OneDriveItemDTO): Long

    fun update(id: Long, oneDriveItemDTO: OneDriveItemDTO)

    fun delete(id: Long)

    /**
     * Upsert an item from Graph delta API data.
     * Finds by (graphItemId, driveId) - creates if not found, updates if found.
     *
     * @return The item ID (new or existing)
     */
    fun upsertFromGraphItem(dto: OneDriveItemDTO): Long

    /**
     * Mark an item as deleted (soft delete).
     */
    fun markAsDeleted(graphItemId: String, driveId: String)

    /**
     * Find items with METADATA_ONLY status eligible for content download.
     */
    fun findMetadataOnlyForDownload(accountId: Long, pageable: Pageable): Page<OneDriveItemDTO>

    /**
     * Count items eligible for content download.
     */
    fun countMetadataOnlyForDownload(accountId: Long): Long

    /**
     * Update an item after successful content download and text extraction.
     */
    fun updateContentAndComplete(
        id: Long,
        bodyText: String?,
        bodySize: Long?,
        contentType: String?,
        author: String?,
        title: String?,
        pageCount: Int?
    )

    /**
     * Mark an item's content download as failed.
     */
    fun markDownloadFailed(id: Long, error: String?)

    /**
     * Find items that have body text but haven't been chunked.
     */
    fun findUnchunkedByAccount(accountId: Long, pageable: Pageable): Page<OneDriveItemDTO>

    /**
     * Count unchunked items for an account.
     */
    fun countUnchunkedByAccount(accountId: Long): Long

    /**
     * Mark an item as chunked.
     */
    fun markAsChunked(id: Long)

    /**
     * Count items for an account.
     */
    fun countByAccount(accountId: Long): Long

}
