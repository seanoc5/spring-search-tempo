package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import com.oconeco.spring_search_tempo.base.model.SmartDiffKind
import com.oconeco.spring_search_tempo.base.model.SmartDiffLine
import com.oconeco.spring_search_tempo.base.model.SmartDiffSummary

/**
 * Walks a java-diff-utils patch for a single pair of text streams and emits the
 * UI-shaped [SmartDiffLine]s plus a [SmartDiffSummary] of the changes.
 *
 * Extracted from [DocxSmartDiffStrategy] so per-slide diffing in
 * [PptxSmartDiffStrategy] (issue #145) reuses the exact same delta-walking
 * semantics — only the input segmentation differs.
 */
internal object LineDiffEmitter {

    data class Output(
        val lines: List<SmartDiffLine>,
        val summary: SmartDiffSummary,
    )

    /**
     * Diff [oldLines] against [newLines]. Line numbers in the produced
     * [SmartDiffLine]s are 1-based positions in the local stream; callers
     * that want global numbering can post-process.
     */
    fun emit(oldLines: List<String>, newLines: List<String>): Output {
        val patch = DiffUtils.diff(oldLines, newLines)
        val out = mutableListOf<SmartDiffLine>()
        var oldIdx = 0
        var newIdx = 0
        var inserted = 0
        var deleted = 0
        var changed = 0
        var unchanged = 0

        for (delta in patch.deltas) {
            while (oldIdx < delta.source.position) {
                out += SmartDiffLine(
                    kind = SmartDiffKind.UNCHANGED,
                    oldLineNo = oldIdx + 1,
                    newLineNo = newIdx + 1,
                    text = oldLines[oldIdx],
                )
                oldIdx++
                newIdx++
                unchanged++
            }

            when (delta.type) {
                DeltaType.INSERT -> {
                    for ((i, line) in delta.target.lines.withIndex()) {
                        out += SmartDiffLine(
                            kind = SmartDiffKind.INSERTED,
                            newLineNo = newIdx + i + 1,
                            text = line,
                        )
                    }
                    newIdx += delta.target.lines.size
                    inserted += delta.target.lines.size
                }
                DeltaType.DELETE -> {
                    for ((i, line) in delta.source.lines.withIndex()) {
                        out += SmartDiffLine(
                            kind = SmartDiffKind.DELETED,
                            oldLineNo = oldIdx + i + 1,
                            text = line,
                        )
                    }
                    oldIdx += delta.source.lines.size
                    deleted += delta.source.lines.size
                }
                DeltaType.CHANGE -> {
                    val srcLines = delta.source.lines
                    val tgtLines = delta.target.lines
                    val paired = minOf(srcLines.size, tgtLines.size)
                    for (i in 0 until paired) {
                        out += SmartDiffLine(
                            kind = SmartDiffKind.CHANGED,
                            oldLineNo = oldIdx + i + 1,
                            newLineNo = newIdx + i + 1,
                            oldText = srcLines[i],
                            newText = tgtLines[i],
                        )
                    }
                    if (srcLines.size > tgtLines.size) {
                        for (i in paired until srcLines.size) {
                            out += SmartDiffLine(
                                kind = SmartDiffKind.DELETED,
                                oldLineNo = oldIdx + i + 1,
                                text = srcLines[i],
                            )
                        }
                    } else if (tgtLines.size > srcLines.size) {
                        for (i in paired until tgtLines.size) {
                            out += SmartDiffLine(
                                kind = SmartDiffKind.INSERTED,
                                newLineNo = newIdx + i + 1,
                                text = tgtLines[i],
                            )
                        }
                    }
                    oldIdx += srcLines.size
                    newIdx += tgtLines.size
                    changed += paired
                    if (srcLines.size > tgtLines.size) deleted += srcLines.size - tgtLines.size
                    if (tgtLines.size > srcLines.size) inserted += tgtLines.size - srcLines.size
                }
                DeltaType.EQUAL -> {
                    for ((i, line) in delta.source.lines.withIndex()) {
                        out += SmartDiffLine(
                            kind = SmartDiffKind.UNCHANGED,
                            oldLineNo = oldIdx + i + 1,
                            newLineNo = newIdx + i + 1,
                            text = line,
                        )
                    }
                    oldIdx += delta.source.lines.size
                    newIdx += delta.target.lines.size
                    unchanged += delta.source.lines.size
                }
            }
        }
        while (oldIdx < oldLines.size) {
            out += SmartDiffLine(
                kind = SmartDiffKind.UNCHANGED,
                oldLineNo = oldIdx + 1,
                newLineNo = newIdx + 1,
                text = oldLines[oldIdx],
            )
            oldIdx++
            newIdx++
            unchanged++
        }

        return Output(out, SmartDiffSummary(inserted, deleted, changed, unchanged))
    }
}
