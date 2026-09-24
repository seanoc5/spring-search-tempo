package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.SmartDiffKind
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Strategy-level coverage for [XlsxSmartDiffStrategy] — runs without the
 * Spring context for fast feedback on the POI extraction + java-diff-utils
 * mapping (issue #166), mirroring [DocxSmartDiffStrategyTest].
 */
@DisplayName("XlsxSmartDiffStrategy (issue #166)")
class XlsxSmartDiffStrategyTest {

    @TempDir
    lateinit var tmp: Path

    private val strategy = XlsxSmartDiffStrategy()

    @Test
    @DisplayName("identical workbooks produce zero changes")
    fun identicalWorkbooks() {
        val sheets = listOf("Sheet1" to listOf(listOf("a", "b"), listOf("1", "2")))
        val a = makeFile(writeXlsx("a.xlsx", sheets), id = 1L)
        val b = makeFile(writeXlsx("b.xlsx", sheets), id = 2L)

        val result = strategy.diff(a, b)

        assertThat(result.summary.totalChanges).isZero()
        assertThat(result.sections).hasSize(1)
        assertThat(result.sections[0].kind).isEqualTo(SmartDiffKind.UNCHANGED)
    }

    @Test
    @DisplayName("edited cell in one sheet reports a CHANGED section with old/new text")
    fun editedCell() {
        val a = makeFile(
            writeXlsx("a.xlsx", listOf("Sheet1" to listOf(listOf("name", "score"), listOf("alice", "10")))),
            id = 1L,
        )
        val b = makeFile(
            writeXlsx("b.xlsx", listOf("Sheet1" to listOf(listOf("name", "score"), listOf("alice", "42")))),
            id = 2L,
        )

        val result = strategy.diff(a, b)

        assertThat(result.sections).hasSize(1)
        assertThat(result.sections[0].kind).isEqualTo(SmartDiffKind.CHANGED)
        assertThat(result.summary.changed).isGreaterThan(0)
        assertThat(result.lines).anySatisfy {
            assertThat(it.oldText ?: it.text ?: "").contains("10")
        }
        assertThat(result.lines).anySatisfy {
            assertThat(it.newText ?: it.text ?: "").contains("42")
        }
    }

    @Test
    @DisplayName("sheet added in new workbook reports an INSERTED section")
    fun addedSheet() {
        val a = makeFile(writeXlsx("a.xlsx", listOf("Sheet1" to listOf(listOf("x")))), id = 1L)
        val b = makeFile(
            writeXlsx(
                "b.xlsx",
                listOf(
                    "Sheet1" to listOf(listOf("x")),
                    "Sheet2" to listOf(listOf("new", "sheet")),
                ),
            ),
            id = 2L,
        )

        val result = strategy.diff(a, b)

        assertThat(result.sections).hasSize(2)
        assertThat(result.sections[0].kind).isEqualTo(SmartDiffKind.UNCHANGED)
        assertThat(result.sections[1].kind).isEqualTo(SmartDiffKind.INSERTED)
        assertThat(result.sections[1].label).contains("Sheet2").contains("[new]")
        assertThat(result.summary.inserted).isGreaterThan(0)
    }

    @Test
    @DisplayName("sheet removed in new workbook reports a DELETED section")
    fun removedSheet() {
        val a = makeFile(
            writeXlsx(
                "a.xlsx",
                listOf(
                    "Sheet1" to listOf(listOf("x")),
                    "Sheet2" to listOf(listOf("gone")),
                ),
            ),
            id = 1L,
        )
        val b = makeFile(writeXlsx("b.xlsx", listOf("Sheet1" to listOf(listOf("x")))), id = 2L)

        val result = strategy.diff(a, b)

        assertThat(result.sections).hasSize(2)
        assertThat(result.sections[0].kind).isEqualTo(SmartDiffKind.UNCHANGED)
        assertThat(result.sections[1].kind).isEqualTo(SmartDiffKind.DELETED)
        assertThat(result.sections[1].label).contains("Sheet2").contains("[removed]")
        assertThat(result.summary.deleted).isGreaterThan(0)
    }

    @Test
    @DisplayName("empty workbooks (no sheets) produce no sections and no changes")
    fun emptyWorkbooks() {
        val a = makeFile(writeXlsx("a.xlsx", emptyList()), id = 1L)
        val b = makeFile(writeXlsx("b.xlsx", emptyList()), id = 2L)

        val result = strategy.diff(a, b)

        assertThat(result.sections).isEmpty()
        assertThat(result.lines).isEmpty()
        assertThat(result.summary.totalChanges).isZero()
    }

    @Test
    @DisplayName("a sheet with zero rows diffed against itself is UNCHANGED with no lines")
    fun emptySheet() {
        val a = makeFile(writeXlsx("a.xlsx", listOf("Sheet1" to emptyList())), id = 1L)
        val b = makeFile(writeXlsx("b.xlsx", listOf("Sheet1" to emptyList())), id = 2L)

        val result = strategy.diff(a, b)

        assertThat(result.sections).hasSize(1)
        assertThat(result.sections[0].kind).isEqualTo(SmartDiffKind.UNCHANGED)
        assertThat(result.lines).isEmpty()
    }

    @Nested
    @DisplayName("alignSheets()")
    inner class AlignSheets {

        @Test
        @DisplayName("same sheet names pair every sheet regardless of position")
        fun samePairs() {
            val old = listOf(sheet("A", listOf("1")), sheet("B", listOf("2")))
            val new = listOf(sheet("B", listOf("2")), sheet("A", listOf("1")))
            val alignments = strategy.alignSheets(old, new)
            assertThat(alignments).hasSize(2)
                .allSatisfy { assertThat(it).isInstanceOf(XlsxSmartDiffStrategy.SheetAlignment.Paired::class.java) }
        }

        @Test
        @DisplayName("renamed sheet is reported as delete + insert, not a rename")
        fun renameIsRemoveAndAdd() {
            val old = listOf(sheet("Q1", listOf("data")))
            val new = listOf(sheet("Q1 Final", listOf("data")))
            val alignments = strategy.alignSheets(old, new)
            assertThat(alignments).hasSize(2)
            assertThat(alignments[0]).isInstanceOf(XlsxSmartDiffStrategy.SheetAlignment.Deleted::class.java)
            assertThat(alignments[1]).isInstanceOf(XlsxSmartDiffStrategy.SheetAlignment.Inserted::class.java)
        }
    }

    private fun sheet(name: String, rows: List<String>) = XlsxSmartDiffStrategy.SheetContent(name, rows)

    private fun writeXlsx(name: String, sheets: List<Pair<String, List<List<String>>>>): Path {
        val path = tmp.resolve(name)
        XSSFWorkbook().use { workbook ->
            for ((sheetName, rows) in sheets) {
                val sheet = workbook.createSheet(sheetName)
                rows.forEachIndexed { rowIdx, cells ->
                    val row = sheet.createRow(rowIdx)
                    cells.forEachIndexed { cellIdx, value -> row.createCell(cellIdx).setCellValue(value) }
                }
            }
            Files.newOutputStream(path).use { workbook.write(it) }
        }
        return path
    }

    private fun makeFile(path: Path, id: Long): FSFile = FSFile().also {
        it.id = id
        it.uri = path.toString()
        it.label = path.fileName.toString()
        it.contentType = XlsxSmartDiffStrategy.XLSX_CONTENT_TYPES.first()
    }
}
