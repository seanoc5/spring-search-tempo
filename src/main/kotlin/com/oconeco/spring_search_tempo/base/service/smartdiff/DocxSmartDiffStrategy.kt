package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.SmartDiffKind
import com.oconeco.spring_search_tempo.base.model.SmartDiffLine
import com.oconeco.spring_search_tempo.base.model.SmartDiffResult
import com.oconeco.spring_search_tempo.base.model.SmartDiffSummary
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.io.path.Path

/**
 * Smart-diff strategy for Microsoft Word `.docx` files.
 *
 * Extracts paragraph text via Apache POI's [XWPFDocument] and feeds the two
 * paragraph streams to java-diff-utils. Tables are flattened into the
 * paragraph stream with a marker prefix; headers/footers/footnotes are
 * intentionally ignored per spike #126 §2 ("MVP: ignore — they're rarely the
 * answer to 'what changed'").
 *
 * Issue #144.
 */
@Component
class DocxSmartDiffStrategy : SmartDiffStrategy {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun supportedContentTypes(): Set<String> = DOCX_CONTENT_TYPES

    override fun diff(oldFile: FSFile, newFile: FSFile): SmartDiffResult {
        val oldParagraphs = readParagraphs(oldFile)
        val newParagraphs = readParagraphs(newFile)

        val patch = DiffUtils.diff(oldParagraphs, newParagraphs)

        val lines = mutableListOf<SmartDiffLine>()
        var oldIdx = 0
        var newIdx = 0
        var inserted = 0
        var deleted = 0
        var changed = 0
        var unchanged = 0

        for (delta in patch.deltas) {
            // Emit the unchanged context between the previous delta and this one.
            while (oldIdx < delta.source.position) {
                lines += SmartDiffLine(
                    kind = SmartDiffKind.UNCHANGED,
                    oldLineNo = oldIdx + 1,
                    newLineNo = newIdx + 1,
                    text = oldParagraphs[oldIdx],
                )
                oldIdx++
                newIdx++
                unchanged++
            }

            when (delta.type) {
                DeltaType.INSERT -> {
                    for ((i, line) in delta.target.lines.withIndex()) {
                        lines += SmartDiffLine(
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
                        lines += SmartDiffLine(
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
                        lines += SmartDiffLine(
                            kind = SmartDiffKind.CHANGED,
                            oldLineNo = oldIdx + i + 1,
                            newLineNo = newIdx + i + 1,
                            oldText = srcLines[i],
                            newText = tgtLines[i],
                        )
                    }
                    if (srcLines.size > tgtLines.size) {
                        for (i in paired until srcLines.size) {
                            lines += SmartDiffLine(
                                kind = SmartDiffKind.DELETED,
                                oldLineNo = oldIdx + i + 1,
                                text = srcLines[i],
                            )
                        }
                    } else if (tgtLines.size > srcLines.size) {
                        for (i in paired until tgtLines.size) {
                            lines += SmartDiffLine(
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
                    // EQUAL deltas shouldn't appear in DiffUtils.diff output, but if
                    // they did, treat them as the context-fill block above does.
                    for ((i, line) in delta.source.lines.withIndex()) {
                        lines += SmartDiffLine(
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
        // Trailing unchanged paragraphs after the last delta.
        while (oldIdx < oldParagraphs.size) {
            lines += SmartDiffLine(
                kind = SmartDiffKind.UNCHANGED,
                oldLineNo = oldIdx + 1,
                newLineNo = newIdx + 1,
                text = oldParagraphs[oldIdx],
            )
            oldIdx++
            newIdx++
            unchanged++
        }

        return SmartDiffResult(
            oldFileId = oldFile.id!!,
            newFileId = newFile.id!!,
            oldLabel = oldFile.label,
            newLabel = newFile.label,
            contentType = newFile.contentType ?: oldFile.contentType,
            lines = lines,
            summary = SmartDiffSummary(inserted, deleted, changed, unchanged),
            notes = NOTES,
        )
    }

    private fun readParagraphs(file: FSFile): List<String> {
        val uri = file.uri
            ?: throw IllegalArgumentException("FSFile ${file.id} has no uri")
        val path = Path(uri)
        if (!Files.exists(path)) {
            throw NoSuchFileException(uri)
        }
        return Files.newInputStream(path).use { input ->
            XWPFDocument(input).use { doc ->
                buildList {
                    for (paragraph in doc.paragraphs) {
                        val text = paragraph.text?.trim().orEmpty()
                        if (text.isNotEmpty()) add(text)
                    }
                    for (table in doc.tables) {
                        for (row in table.rows) {
                            for (cell in row.tableCells) {
                                val cellText = cell.text?.trim().orEmpty()
                                if (cellText.isNotEmpty()) add("$TABLE_CELL_MARKER $cellText")
                            }
                        }
                    }
                }
            }
        }.also {
            log.debug("Extracted {} paragraphs from {}", it.size, uri)
        }
    }

    companion object {
        /**
         * OOXML wordprocessingML MIME types we accept. Tika typically emits the
         * canonical first entry; the second is the legacy Microsoft form some
         * tools still produce.
         */
        val DOCX_CONTENT_TYPES = setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-word.document.macroenabled.12",
            "application/msword.document",
        )

        const val TABLE_CELL_MARKER = "[table]"

        private val NOTES = listOf(
            "Headers, footers, and footnotes are ignored.",
            "Tables are flattened into the paragraph stream and prefixed with [table].",
            "Tracked-changes layer is ignored; the 'current' view is diffed.",
        )
    }
}
