package com.oconeco.spring_search_tempo.base.model

/**
 * Outcome of a smart-diff between two FSFile versions. Carries enough
 * structure for the UI to render either an inline or side-by-side view
 * without further parsing.
 *
 * The unit of a [SmartDiffLine] is intentionally format-specific (paragraphs
 * for .docx, text-frame entries for .pptx, etc.); the contract for the UI is
 * just "a sequence of lines, each tagged with a change kind".
 *
 * Issue #144 — shared smart-diff infrastructure.
 */
data class SmartDiffResult(
    val oldFileId: Long,
    val newFileId: Long,
    val oldLabel: String?,
    val newLabel: String?,
    val contentType: String?,
    val lines: List<SmartDiffLine>,
    val summary: SmartDiffSummary,
    /** Free-form notes the strategy wants to surface (e.g. "tracked changes ignored"). */
    val notes: List<String> = emptyList(),
)

/**
 * One paragraph / line of the unified diff stream.
 *
 * For [SmartDiffKind.UNCHANGED] and [SmartDiffKind.DELETED] lines, [oldLineNo]
 * is set and [text] is the old version's content. For [SmartDiffKind.INSERTED],
 * [newLineNo] is set and [text] is the new version's content. For
 * [SmartDiffKind.CHANGED], both line numbers and an [oldText]/[newText] pair
 * are populated and [text] is null.
 */
data class SmartDiffLine(
    val kind: SmartDiffKind,
    val oldLineNo: Int? = null,
    val newLineNo: Int? = null,
    val text: String? = null,
    val oldText: String? = null,
    val newText: String? = null,
)

enum class SmartDiffKind {
    UNCHANGED,
    INSERTED,
    DELETED,
    CHANGED,
}

data class SmartDiffSummary(
    val inserted: Int,
    val deleted: Int,
    val changed: Int,
    val unchanged: Int,
) {
    val totalChanges: Int get() = inserted + deleted + changed
}
