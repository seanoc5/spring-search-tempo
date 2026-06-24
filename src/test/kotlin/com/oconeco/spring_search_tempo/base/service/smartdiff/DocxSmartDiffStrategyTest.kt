package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.SmartDiffKind
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Strategy-level coverage for [DocxSmartDiffStrategy] — runs without the
 * Spring context for fast feedback on the POI extraction + java-diff-utils
 * mapping (issue #144). The end-to-end IT in [SmartDiffServiceIT] exercises
 * the same code path through DI.
 */
@DisplayName("DocxSmartDiffStrategy (issue #144)")
class DocxSmartDiffStrategyTest {

    @TempDir
    lateinit var tmp: Path

    private val strategy = DocxSmartDiffStrategy()

    @Test
    @DisplayName("identical paragraph streams produce zero changes")
    fun identicalDocs() {
        val paragraphs = listOf("Hello world.", "This is a test.")
        val a = makeFile(writeDocx("a.docx", paragraphs), id = 1L)
        val b = makeFile(writeDocx("b.docx", paragraphs), id = 2L)

        val result = strategy.diff(a, b)

        assertThat(result.summary.totalChanges).isZero()
        assertThat(result.summary.unchanged).isEqualTo(2)
        assertThat(result.lines).allSatisfy {
            assertThat(it.kind).isEqualTo(SmartDiffKind.UNCHANGED)
        }
    }

    @Test
    @DisplayName("all-new-paragraphs case is reported as inserts")
    fun allInserts() {
        val a = makeFile(writeDocx("empty.docx", listOf("   ")), id = 1L)
        val b = makeFile(writeDocx("filled.docx", listOf("alpha", "beta")), id = 2L)

        val result = strategy.diff(a, b)

        assertThat(result.summary.inserted).isEqualTo(2)
        assertThat(result.summary.deleted).isEqualTo(0)
    }

    @Test
    @DisplayName("changed delta with more new lines than old lines spills the excess into inserts")
    fun changeSpillsToInsert() {
        val a = makeFile(writeDocx("a.docx", listOf("first", "old line")), id = 1L)
        val b = makeFile(writeDocx("b.docx", listOf("first", "new line a", "new line b")), id = 2L)

        val result = strategy.diff(a, b)

        // One CHANGED (paired old↔new line a) + one INSERTED (extra new line b)
        assertThat(result.summary.changed).isEqualTo(1)
        assertThat(result.summary.inserted).isEqualTo(1)
        assertThat(result.summary.deleted).isEqualTo(0)
        assertThat(result.summary.unchanged).isEqualTo(1)
    }

    @Test
    @DisplayName("table cells are flattened into the paragraph stream with a marker prefix")
    fun tableCellsFlattened() {
        val withTable = tmp.resolve("table.docx")
        XWPFDocument().use { doc ->
            doc.createParagraph().createRun().setText("Before table")
            val table = doc.createTable(1, 2)
            table.getRow(0).getCell(0).text = "cell-a"
            table.getRow(0).getCell(1).text = "cell-b"
            Files.newOutputStream(withTable).use { doc.write(it) }
        }
        val empty = makeFile(writeDocx("empty.docx", listOf("Before table")), id = 1L)
        val tabled = makeFile(withTable, id = 2L)

        val result = strategy.diff(empty, tabled)

        assertThat(result.summary.inserted).isGreaterThanOrEqualTo(2)
        assertThat(result.lines).anySatisfy {
            assertThat(it.text ?: "").startsWith(DocxSmartDiffStrategy.TABLE_CELL_MARKER)
        }
    }

    private fun writeDocx(name: String, paragraphs: List<String>): Path {
        val path = tmp.resolve(name)
        XWPFDocument().use { doc ->
            for (text in paragraphs) {
                doc.createParagraph().createRun().setText(text)
            }
            Files.newOutputStream(path).use { doc.write(it) }
        }
        return path
    }

    private fun makeFile(path: Path, id: Long): FSFile = FSFile().also {
        it.id = id
        it.uri = path.toString()
        it.label = path.fileName.toString()
        it.contentType = DocxSmartDiffStrategy.DOCX_CONTENT_TYPES.first()
    }
}
