package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.base.repos.FolderSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Snapshot rotation for the folder audit (issue #105).
 *
 * After a successful audit run, prune `folder_snapshot` and
 * `folder_audit_run` rows older than the latest [retainRuns] runs.
 * Snapshots are disposable scratch — keeping the last 4 by default gives
 * an operator a small window to compare runs without unbounded growth
 * (one row per directory walked, every week, adds up fast on a real
 * filesystem).
 *
 * `hidden_gem_resolution` rows (the audit's only durable output, from a
 * future issue B) are deliberately NOT touched here — they hang off the
 * snapshot table via FK in the schema sense but represent the operator's
 * recorded decisions, not scratch.
 *
 * Deletion ordering: snapshots first (FK from `folder_snapshot.audit_run_id`
 * to `folder_audit_run.id`), runs second.
 */
@Service
class FolderAuditRetentionService(
    private val folderAuditRunRepository: FolderAuditRunRepository,
    private val folderSnapshotRepository: FolderSnapshotRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(FolderAuditRetentionService::class.java)
    }

    /**
     * Keep the latest [retainRuns] runs; delete older snapshots and runs.
     *
     * - `retainRuns <= 0` is treated as a no-op (defensive: refuse to wipe
     *   the table outright on a misconfigured `app.audit.retain-runs`).
     * - If fewer than `retainRuns` runs exist, no-op.
     */
    @Transactional
    fun rotate(retainRuns: Int) {
        if (retainRuns <= 0) {
            log.warn(
                "Folder audit retention skipped: app.audit.retain-runs={} is not positive",
                retainRuns
            )
            return
        }

        val keepIds = folderAuditRunRepository.findRecentIds(PageRequest.of(0, retainRuns))
        if (keepIds.isEmpty()) {
            log.debug("Folder audit retention: no runs in the database; nothing to prune")
            return
        }

        val totalRuns = folderAuditRunRepository.count()
        if (totalRuns <= keepIds.size.toLong()) {
            log.debug(
                "Folder audit retention: totalRuns={} <= retainRuns={}, nothing to prune",
                totalRuns, retainRuns
            )
            return
        }

        val deletedSnapshots = folderSnapshotRepository.deleteByAuditRunIdNotIn(keepIds)
        val deletedRuns = folderAuditRunRepository.deleteByIdNotIn(keepIds)
        log.info(
            "Folder audit retention rotated: kept {} runs, deleted {} runs and {} snapshots",
            keepIds.size, deletedRuns, deletedSnapshots
        )
    }
}
