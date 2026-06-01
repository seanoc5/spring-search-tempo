package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.DiscoveredFolder
import com.oconeco.spring_search_tempo.base.domain.SuggestedStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface DiscoveredFolderRepository : JpaRepository<DiscoveredFolder, Long> {

    fun findBySessionId(sessionId: Long): List<DiscoveredFolder>

    fun findBySessionIdAndDepth(sessionId: Long, depth: Int): List<DiscoveredFolder>

    @Query("""
        SELECT f FROM DiscoveredFolder f
        WHERE f.session.id = :sessionId
          AND (
            (:parentPath IS NULL AND f.parentPath IS NULL)
            OR f.parentPath = :parentPath
          )
        ORDER BY f.path
    """)
    fun findBySessionIdAndParentPath(sessionId: Long, parentPath: String?): List<DiscoveredFolder>

    @Query("SELECT f FROM DiscoveredFolder f WHERE f.session.id = :sessionId ORDER BY f.path")
    fun findBySessionIdOrderByPath(sessionId: Long): List<DiscoveredFolder>

    @Query("SELECT f FROM DiscoveredFolder f WHERE f.session.id = :sessionId AND f.depth <= :maxDepth ORDER BY f.path")
    fun findBySessionIdAndMaxDepth(sessionId: Long, maxDepth: Int): List<DiscoveredFolder>

    @Query("""
        SELECT f FROM DiscoveredFolder f
        WHERE f.session.id = :sessionId
          AND f.path IN :paths
        ORDER BY f.path
    """)
    fun findBySessionIdAndPathIn(sessionId: Long, paths: Collection<String>): List<DiscoveredFolder>

    fun countBySessionId(sessionId: Long): Long

    fun countBySessionIdAndClassified(sessionId: Long, classified: Boolean): Long

    fun countBySessionIdAndAssignedStatus(sessionId: Long, status: AnalysisStatus): Long

    fun countBySessionIdAndSuggestedStatus(sessionId: Long, status: SuggestedStatus): Long

    @Modifying
    @Query("UPDATE DiscoveredFolder f SET f.assignedStatus = :status, f.classified = true WHERE f.id = :folderId")
    fun updateAssignedStatus(folderId: Long, status: AnalysisStatus): Int

    @Modifying
    @Query("""
        UPDATE DiscoveredFolder f
        SET f.assignedStatus = :status, f.classified = true
        WHERE f.session.id = :sessionId
          AND (
            f.path = :folderPath
            OR (
              (f.path LIKE :slashPrefix OR f.path LIKE :backslashPrefix)
              AND f.assignedStatus IS NULL
            )
          )
    """)
    fun updateAssignedStatusForSubtreeUnassigned(
        sessionId: Long,
        folderPath: String,
        slashPrefix: String,
        backslashPrefix: String,
        status: AnalysisStatus
    ): Int

    @Modifying
    @Query("""
        UPDATE DiscoveredFolder f
        SET f.assignedStatus = :status, f.classified = true
        WHERE f.session.id = :sessionId AND f.suggestedStatus = :suggested AND f.classified = false
    """)
    fun applySuggestedStatus(sessionId: Long, suggested: SuggestedStatus, status: AnalysisStatus): Int

    @Query("""
        SELECT f FROM DiscoveredFolder f
        WHERE f.session.id = :sessionId
        AND f.assignedStatus = :status
        ORDER BY f.path
    """)
    fun findBySessionIdAndAssignedStatus(sessionId: Long, status: AnalysisStatus): List<DiscoveredFolder>

    @Query("""
        SELECT f FROM DiscoveredFolder f
        WHERE f.session.id = :sessionId
        AND f.assignedStatus = :status
        ORDER BY f.path
    """)
    fun findBySessionIdAndAssignedStatus(
        sessionId: Long,
        status: AnalysisStatus,
        pageable: Pageable
    ): Page<DiscoveredFolder>

    fun findBySessionIdAndAssignedStatusAndPathContainingIgnoreCase(
        sessionId: Long,
        status: AnalysisStatus,
        path: String,
        pageable: Pageable
    ): Page<DiscoveredFolder>
}
