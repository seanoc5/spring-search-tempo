package com.oconeco.spring_search_tempo.batch.audit

import com.oconeco.spring_search_tempo.base.repos.FolderAuditRunRepository
import com.oconeco.spring_search_tempo.base.repos.FolderSnapshotRepository
import org.springframework.stereotype.Service
import kotlin.math.abs

/**
 * Computes operator-facing reconciliation views over an existing folder-audit
 * run (issue #138). Read-only: no new audit work, no schema, no scheduler hook —
 * just delta math and a per-top-level-path breakdown so the operator can
 * sanity-check the audit's total against `find / -type d | wc -l`.
 */
@Service
class FolderAuditReconciliationService(
    private val folderAuditRunRepository: FolderAuditRunRepository,
    private val folderSnapshotRepository: FolderSnapshotRepository,
) {

    data class PathBreakdownRow(
        val path: String,
        val auditCount: Long,
        val underSkipPattern: String?,
        val foldersWalkedInto: Long,
    ) {
        val isSkipRoot: Boolean get() = underSkipPattern != null
    }

    data class ReconciliationResult(
        val runId: Long,
        val auditTotal: Long,
        val groundTruthTotal: Long,
        val delta: Long,
        val percentDelta: Double,
        val severity: Severity,
        val sourceCommand: String?,
    ) {
        val absDelta: Long get() = abs(delta)
    }

    enum class Severity(val badgeClass: String, val label: String) {
        GREEN("bg-success", "≤1%"),
        YELLOW("bg-warning text-dark", "≤5%"),
        RED("bg-danger", ">5%"),
    }

    /**
     * Per-top-level-path breakdown for the run: one row per immediate child of
     * the audit's start path. `auditCount` is the inclusive subtree count;
     * `foldersWalkedInto` excludes the row itself. SKIP-rooted rows render a
     * "+N walked into" hint in the template so the operator understands that
     * SKIP subtrees still contribute peek-depth folders to the total.
     */
    fun computeBreakdown(runId: Long): List<PathBreakdownRow> {
        val root = folderSnapshotRepository.findFirstByAuditRunIdOrderByDepthAsc(runId)
            ?: return emptyList()
        val rootPath = root.path ?: return emptyList()
        val children = folderSnapshotRepository
            .findByAuditRunIdAndParentPathOrderByPathAsc(runId, rootPath)
        return children.map { child ->
            val childPath = child.path ?: ""
            val subtreeCount = folderSnapshotRepository.countSubtree(runId, childPath)
            PathBreakdownRow(
                path = childPath,
                auditCount = subtreeCount,
                underSkipPattern = child.underSkipPattern,
                foldersWalkedInto = (subtreeCount - 1).coerceAtLeast(0),
            )
        }
    }

    fun reconcile(
        runId: Long,
        groundTruthTotal: Long,
        sourceCommand: String?,
    ): ReconciliationResult {
        val run = folderAuditRunRepository.findById(runId)
            .orElseThrow { IllegalArgumentException("No folder-audit run with id=$runId") }
        val auditTotal = run.totalFolders
        val delta = groundTruthTotal - auditTotal
        val percentDelta = when {
            auditTotal > 0 -> abs(delta).toDouble() / auditTotal.toDouble() * 100.0
            groundTruthTotal == 0L -> 0.0
            else -> 100.0
        }
        val severity = when {
            percentDelta <= 1.0 -> Severity.GREEN
            percentDelta <= 5.0 -> Severity.YELLOW
            else -> Severity.RED
        }
        return ReconciliationResult(
            runId = runId,
            auditTotal = auditTotal,
            groundTruthTotal = groundTruthTotal,
            delta = delta,
            percentDelta = percentDelta,
            severity = severity,
            sourceCommand = sourceCommand?.takeIf { it.isNotBlank() },
        )
    }
}
