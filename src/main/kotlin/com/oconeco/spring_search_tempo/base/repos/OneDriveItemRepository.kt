package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.OneDriveFetchStatus
import com.oconeco.spring_search_tempo.base.domain.OneDriveItem
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param


interface OneDriveItemRepository : JpaRepository<OneDriveItem, Long> {

    fun findByGraphItemIdAndDriveId(graphItemId: String, driveId: String): OneDriveItem?

    fun findByOneDriveAccountIdAndFetchStatus(
        accountId: Long,
        fetchStatus: OneDriveFetchStatus,
        pageable: Pageable
    ): Page<OneDriveItem>

    fun countByOneDriveAccountIdAndFetchStatus(
        accountId: Long,
        fetchStatus: OneDriveFetchStatus
    ): Long

    fun countByOneDriveAccountId(accountId: Long): Long

    /**
     * Mark an item as deleted (soft delete).
     */
    @Modifying
    @Query("UPDATE OneDriveItem i SET i.isDeleted = true WHERE i.graphItemId = :graphItemId AND i.driveId = :driveId")
    fun markAsDeleted(@Param("graphItemId") graphItemId: String, @Param("driveId") driveId: String): Int

    /**
     * Find existing graph item IDs from a set of candidates.
     * Used for batch duplicate detection during sync.
     */
    @Query("SELECT i.graphItemId FROM OneDriveItem i WHERE i.driveId = :driveId AND i.graphItemId IN :graphItemIds")
    fun findExistingGraphItemIds(
        @Param("driveId") driveId: String,
        @Param("graphItemIds") graphItemIds: Collection<String>
    ): List<String>

    /**
     * Find items that have body text but haven't been chunked yet.
     */
    fun findByOneDriveAccountIdAndBodyTextIsNotNullAndChunkedAtIsNullAndIsFolderFalse(
        accountId: Long,
        pageable: Pageable
    ): Page<OneDriveItem>

    /**
     * Count items needing chunking.
     */
    @Query("""
        SELECT COUNT(i) FROM OneDriveItem i
        WHERE i.oneDriveAccount.id = :accountId
          AND i.bodyText IS NOT NULL
          AND i.chunkedAt IS NULL
          AND i.isFolder = false
    """)
    fun countNeedingChunking(@Param("accountId") accountId: Long): Long

    /**
     * Delete all items for an account.
     */
    @Modifying
    fun deleteByOneDriveAccountId(accountId: Long): Int

    /**
     * Find items that are not folders, not deleted, have INDEX or ANALYZE status,
     * and have METADATA_ONLY fetch status (candidates for content download).
     */
    @Query("""
        SELECT i FROM OneDriveItem i
        WHERE i.oneDriveAccount.id = :accountId
          AND i.isFolder = false
          AND i.isDeleted = false
          AND i.fetchStatus = 'METADATA_ONLY'
          AND i.analysisStatus IN ('INDEX', 'ANALYZE', 'SEMANTIC')
    """)
    fun findMetadataOnlyForDownload(
        @Param("accountId") accountId: Long,
        pageable: Pageable
    ): Page<OneDriveItem>

    /**
     * Count items eligible for content download.
     */
    @Query("""
        SELECT COUNT(i) FROM OneDriveItem i
        WHERE i.oneDriveAccount.id = :accountId
          AND i.isFolder = false
          AND i.isDeleted = false
          AND i.fetchStatus = 'METADATA_ONLY'
          AND i.analysisStatus IN ('INDEX', 'ANALYZE', 'SEMANTIC')
    """)
    fun countMetadataOnlyForDownload(@Param("accountId") accountId: Long): Long

}
