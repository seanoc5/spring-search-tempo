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
    /**
     * Optional per-section grouping. When non-empty, the UI renders a jump list at the top and
     * splits the line stream into anchored blocks (one per section). Each section's
     * [lineStartIndex, lineEndIndex) refers to indexes into [lines]. Empty for strategies that
     * have no natural sub-grouping (`.docx`); populated for slide-keyed formats (`.pptx`).
     */
    val sections: List<SmartDiffSection> = emptyList(),
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

/**
 * A contiguous run of [SmartDiffLine]s the strategy wants to surface as a single addressable
 * unit — currently used for per-slide grouping in `.pptx` (issue #145). [key] becomes the
 * HTML anchor id; [label] is what the jump list renders.
 *
 * [kind] reflects the section as a whole: UNCHANGED if every line in the range is UNCHANGED;
 * INSERTED if this section is a wholly-new slide; DELETED if a slide removed in the new
 * version; CHANGED otherwise (paired slides with any edit inside).
 */
data class SmartDiffSection(
    val key: String,
    val label: String,
    val kind: SmartDiffKind,
    val lineStartIndex: Int,
    val lineEndIndex: Int,
)
