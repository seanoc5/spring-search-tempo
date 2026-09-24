package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.SmartDiffKind
import com.oconeco.spring_search_tempo.base.model.SmartDiffLine
import com.oconeco.spring_search_tempo.base.model.SmartDiffResult
import com.oconeco.spring_search_tempo.base.model.SmartDiffSection
import com.oconeco.spring_search_tempo.base.model.SmartDiffSummary
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.io.path.Path

/**
 * Smart-diff strategy for Microsoft Excel `.xlsx` workbooks.
 *
 * Per-sheet text extraction via POI's [XSSFWorkbook] and per-sheet text diff
 * via the shared [LineDiffEmitter] (the same plumbing the `.docx` / `.pptx`
 * strategies use).
 *
 * Spike: [docs/research/smart-diff-tools.md §7](../../../../../../../docs/research/smart-diff-tools.md).
 * Issue #166.
 *
 * ## Alignment heuristic
 *
 * Unlike `.pptx` slides — which have no stable identity and are aligned by
 * position with a title look-ahead — `.xlsx` sheets already carry a stable,
 * unique identity: the sheet name. Alignment is therefore a straight name
 * match, no position/look-ahead heuristic needed:
 *
 * 1. Walk the *old* workbook's sheets in order. A name present in both
 *    workbooks pairs the two sheets (diffed via [LineDiffEmitter]); a name
 *    only in the old workbook is a DELETED section.
 * 2. Walk the *new* workbook's sheets in order and emit an INSERTED section
 *    for any name not already seen in step 1.
 *
 * **Renames are reported as remove+add in v1** — a sheet renamed
 * `"Q1"` → `"Q1 Final"` shows up as `Q1 [removed]` followed by
 * `Q1 Final [new]`, even if every row inside is identical. Detecting renames
 * would need a content-similarity heuristic (cf. the title look-ahead in
 * [PptxSmartDiffStrategy]); deferred until real-world usage shows it's
 * needed.
 *
 * ## Row serialization
 *
 * Each row becomes one diff line: its cells, in column order from the row's
 * first to last populated column, formatted with POI's [DataFormatter] and
 * tab-joined. Formula cells are rendered as their *computed* value (via a
 * [org.apache.poi.ss.usermodel.FormulaEvaluator], not the raw formula text)
 * — that's what changed for a reader comparing two versions. Completely
 * blank rows (and blank trailing/leading cells) are skipped, mirroring how
 * the `.docx` strategy skips blank paragraphs.
 */
@Component
class XlsxSmartDiffStrategy : SmartDiffStrategy {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun supportedContentTypes(): Set<String> = XLSX_CONTENT_TYPES

    override fun diff(oldFile: FSFile, newFile: FSFile): SmartDiffResult {
        val oldSheets = readSheets(oldFile)
        val newSheets = readSheets(newFile)
        val alignments = alignSheets(oldSheets, newSheets)

        val allLines = mutableListOf<SmartDiffLine>()
        val sections = mutableListOf<SmartDiffSection>()
        var totalInserted = 0
        var totalDeleted = 0
        var totalChanged = 0
        var totalUnchanged = 0

        for (alignment in alignments) {
            val startIdx = allLines.size
            val sectionKind: SmartDiffKind
            val sectionKey: String
            val sectionLabel: String

            when (alignment) {
                is SheetAlignment.Paired -> {
                    val emitted = LineDiffEmitter.emit(alignment.old.rows, alignment.new.rows)
                    allLines += emitted.lines
                    totalInserted += emitted.summary.inserted
                    totalDeleted += emitted.summary.deleted
                    totalChanged += emitted.summary.changed
                    totalUnchanged += emitted.summary.unchanged
                    sectionKind = if (emitted.summary.totalChanges == 0) SmartDiffKind.UNCHANGED else SmartDiffKind.CHANGED
                    sectionKey = "sheet-${alignment.old.name}"
                    sectionLabel = "Sheet ${alignment.new.name}"
                }
                is SheetAlignment.Inserted -> {
                    val lines = alignment.new.rows.mapIndexed { i, text ->
                        SmartDiffLine(
                            kind = SmartDiffKind.INSERTED,
                            newLineNo = i + 1,
                            text = text,
                        )
                    }
                    allLines += lines
                    totalInserted += lines.size
                    sectionKind = SmartDiffKind.INSERTED
                    sectionKey = "sheet-${alignment.new.name}"
                    sectionLabel = "Sheet ${alignment.new.name} [new]"
                }
                is SheetAlignment.Deleted -> {
                    val lines = alignment.old.rows.mapIndexed { i, text ->
                        SmartDiffLine(
                            kind = SmartDiffKind.DELETED,
                            oldLineNo = i + 1,
                            text = text,
                        )
                    }
                    allLines += lines
                    totalDeleted += lines.size
                    sectionKind = SmartDiffKind.DELETED
                    sectionKey = "sheet-${alignment.old.name}"
                    sectionLabel = "Sheet ${alignment.old.name} [removed]"
                }
            }

            sections += SmartDiffSection(
                key = sectionKey,
                label = sectionLabel,
                kind = sectionKind,
                lineStartIndex = startIdx,
                lineEndIndex = allLines.size,
            )
        }

        return SmartDiffResult(
            oldFileId = oldFile.id!!,
            newFileId = newFile.id!!,
            oldLabel = oldFile.label,
            newLabel = newFile.label,
            contentType = newFile.contentType ?: oldFile.contentType,
            lines = allLines,
            summary = SmartDiffSummary(totalInserted, totalDeleted, totalChanged, totalUnchanged),
            notes = NOTES,
            sections = sections,
        )
    }

    private fun readSheets(file: FSFile): List<SheetContent> {
        val uri = file.uri
            ?: throw IllegalArgumentException("FSFile ${file.id} has no uri")
        val path = Path(uri)
        if (!Files.exists(path)) {
            throw NoSuchFileException(uri)
        }
        return Files.newInputStream(path).use { input ->
            XSSFWorkbook(input).use { workbook ->
                val evaluator = workbook.creationHelper.createFormulaEvaluator()
                val formatter = DataFormatter()
                (0 until workbook.numberOfSheets).map { idx ->
                    val sheet = workbook.getSheetAt(idx)
                    SheetContent(name = sheet.sheetName, rows = serializeRows(sheet, formatter, evaluator))
                }
            }
        }.also {
            log.debug("Extracted {} sheets from {}", it.size, file.uri)
        }
    }

    private fun serializeRows(
        sheet: XSSFSheet,
        formatter: DataFormatter,
        evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator,
    ): List<String> = buildList {
        for (row in sheet) {
            val line = serializeRow(row, formatter, evaluator)
            if (line.isNotEmpty()) add(line)
        }
    }

    private fun serializeRow(
        row: Row,
        formatter: DataFormatter,
        evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator,
    ): String {
        val lastCell = row.lastCellNum.toInt()
        if (lastCell < 0) return ""
        val cells = (0 until lastCell).map { idx ->
            val cell = row.getCell(idx) ?: return@map ""
            formatter.formatCellValue(cell, evaluator).trim()
        }
        // Drop trailing blank cells so two rows that only differ by unused trailing
        // columns don't register as a spurious change.
        val trimmedEnd = cells.indexOfLast { it.isNotEmpty() }
        if (trimmedEnd < 0) return ""
        return cells.subList(0, trimmedEnd + 1).joinToString("\t")
    }

    internal fun alignSheets(
        oldSheets: List<SheetContent>,
        newSheets: List<SheetContent>,
    ): List<SheetAlignment> {
        val newByName = newSheets.associateBy { it.name }
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SheetAlignment>()

        for (old in oldSheets) {
            val new = newByName[old.name]
            out += if (new != null) SheetAlignment.Paired(old, new) else SheetAlignment.Deleted(old)
            seen += old.name
        }
        for (new in newSheets) {
            if (new.name !in seen) {
                out += SheetAlignment.Inserted(new)
            }
        }
        return out
    }

    /** Per-sheet extracted content. [rows] is the tab-joined, blank-trimmed line stream the diff consumes. */
    internal data class SheetContent(
        val name: String,
        val rows: List<String>,
    )

    internal sealed interface SheetAlignment {
        data class Paired(val old: SheetContent, val new: SheetContent) : SheetAlignment
        data class Inserted(val new: SheetContent) : SheetAlignment
        data class Deleted(val old: SheetContent) : SheetAlignment
    }

    companion object {
        /**
         * OOXML spreadsheetML MIME types we accept. Tika typically emits the
         * canonical first entry; the second is the macro-enabled `.xlsm` form.
         */
        val XLSX_CONTENT_TYPES = setOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel.sheet.macroenabled.12",
        )

        private val NOTES = listOf(
            "Sheets are aligned by name; a rename is reported as a removed sheet plus a new one.",
            "Formulas are diffed by their computed value, not the formula text.",
            "Charts, images, and cell formatting/styles are ignored.",
        )
    }
}
