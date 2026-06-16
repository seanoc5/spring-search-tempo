package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.FolderSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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
     * Count snapshots in the subtree rooted at [path] (inclusive). Used by the
     * folder-audit reconciliation breakdown (issue #138) so the operator can see
     * how many folders the audit walked under each top-level path — including
     * the peek-depth contribution under SKIP roots, which is exactly the drift
     * the reconciliation form is trying to make legible.
     *
     * Implementation note: paths in `folder_snapshot` are stored without
     * trailing slashes (see [com.oconeco.spring_search_tempo.batch.audit.FolderAuditVisitor]),
     * so a child path always has the form `:path` + `/...`. The `OR` form here
     * is required because a plain `LIKE :path%` would also match siblings
     * sharing a prefix (e.g. `/home/sean` and `/home/seanoc5`).
     */
    @Query(
        """
        SELECT COUNT(s) FROM FolderSnapshot s
        WHERE s.auditRun.id = :runId
          AND (s.path = :path OR s.path LIKE CONCAT(:path, '/%'))
        """
    )
    fun countSubtree(@Param("runId") runId: Long, @Param("path") path: String): Long

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

    /**
     * Bulk-delete all snapshots whose audit_run_id is NOT in [keepIds].
     * Used by snapshot rotation (issue #105). Returns the number of rows
     * deleted, which the caller logs / asserts on. Empty `keepIds` would
     * delete every row, so the caller guards against that.
     */
    @Modifying
    @Query("delete from FolderSnapshot s where s.auditRun.id not in :keepIds")
    fun deleteByAuditRunIdNotIn(@Param("keepIds") keepIds: Collection<Long>): Int
}
