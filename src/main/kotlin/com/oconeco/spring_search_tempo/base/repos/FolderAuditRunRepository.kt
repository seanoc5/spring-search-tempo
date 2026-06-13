package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.FolderAuditRun
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FolderAuditRunRepository : JpaRepository<FolderAuditRun, Long> {
    fun findAllByOrderByStartedDesc(pageable: Pageable): List<FolderAuditRun>

    /**
     * IDs of the N most recent runs ordered by start time then id. Used by
     * snapshot rotation (issue #105) to compute the "keep" set; anything
     * not in this set is eligible for pruning. The id tie-breaker is for
     * tests/IT that run audits in rapid succession with the same `started`
     * resolution.
     */
    @Query("select r.id from FolderAuditRun r order by r.started desc, r.id desc")
    fun findRecentIds(pageable: Pageable): List<Long>

    /**
     * Bulk-delete every run whose id is NOT in [keepIds]. Snapshot rows
     * must be deleted first (FK from `folder_snapshot.audit_run_id`).
     */
    @Modifying
    @Query("delete from FolderAuditRun r where r.id not in :keepIds")
    fun deleteByIdNotIn(@Param("keepIds") keepIds: Collection<Long>): Int
}
