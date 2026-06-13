package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.FolderSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FolderSnapshotRepository : JpaRepository<FolderSnapshot, Long> {
    fun countByAuditRunId(auditRunId: Long): Long
    fun countByAuditRunIdAndUnderSkipPatternIsNotNull(auditRunId: Long): Long
    fun countByAuditRunIdAndUnderSkipPatternIsNotNullAndMatchedIndexPatternIsNotNull(auditRunId: Long): Long

    fun findByAuditRunIdAndParentPathOrderByPathAsc(
        auditRunId: Long,
        parentPath: String
    ): List<FolderSnapshot>

    fun findByAuditRunIdAndPath(auditRunId: Long, path: String): FolderSnapshot?

    fun findFirstByAuditRunIdOrderByDepthAsc(auditRunId: Long): FolderSnapshot?

    /**
     * Hidden-gem candidates: folders under a SKIP root whose name also
     * matches an INDEX/ANALYZE pattern, excluding any path that already
     * has a durable resolution row for the run's source_ref.
     */
    @Query(
        """
        SELECT s FROM FolderSnapshot s
        WHERE s.auditRun.id = :runId
          AND s.underSkipPattern IS NOT NULL
          AND s.matchedIndexPattern IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM HiddenGemResolution r
              WHERE r.sourceRef = :sourceRef AND r.path = s.path
          )
        ORDER BY s.path ASC
        """
    )
    fun findUnresolvedHiddenGems(
        @Param("runId") runId: Long,
        @Param("sourceRef") sourceRef: String
    ): List<FolderSnapshot>
}
