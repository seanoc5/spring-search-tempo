package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.SmartDiffService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.SmartDiffKind
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * Issue #144: smart-diff shared infrastructure + .docx strategy.
 *
 * Round-trip through the full Spring stack: register strategies via DI,
 * persist two FSFile rows pointing at on-disk `.docx` files, and assert
 * that `SmartDiffService.diff(...)` returns the expected paragraph-level
 * changes.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("SmartDiffService + DocxSmartDiffStrategy (issue #144)")
class SmartDiffServiceIT : BaseIT() {

    @Autowired
    lateinit var smartDiffService: SmartDiffService

    @Autowired
    lateinit var fsFileRepository: FSFileRepository

    @TempDir
    lateinit var tmp: Path

    @Test
    @DisplayName("docx strategy is registered for the canonical wordprocessingML content-type")
    fun strategyRegistered() {
        assertThat(
            smartDiffService.isSupported(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        ).isTrue()
        assertThat(smartDiffService.isSupported("text/plain")).isFalse()
        assertThat(smartDiffService.isSupported(null)).isFalse()
    }

    @Test
    @DisplayName("diff between two .docx versions reports paragraph-level inserts, deletes, and changes")
    fun docxParagraphLevelDiff() {
        val oldDoc = writeDocx(
            tmp.resolve("report-v1.docx"),
            listOf(
                "Introduction",
                "This is the original draft of the report.",
                "It contains three sections.",
                "Section three discusses next steps.",
            ),
        )
        val newDoc = writeDocx(
            tmp.resolve("report-v2.docx"),
            listOf(
                "Introduction",
                "This is the revised draft of the report.",
                "It contains three sections.",
                "Section three discusses next steps.",
                "Appendix: glossary and references.",
            ),
        )

        val oldFile = persistFile(oldDoc, label = "report.docx", contentHash = "old-hash-".padEnd(64, '0'))
        val newFile = persistFile(newDoc, label = "report.docx", contentHash = "new-hash-".padEnd(64, '0'))

        val result = smartDiffService.diff(oldFileId = oldFile.id!!, newFileId = newFile.id!!)

        assertThat(result.oldFileId).isEqualTo(oldFile.id)
        assertThat(result.newFileId).isEqualTo(newFile.id)
        assertThat(result.summary.inserted).isEqualTo(1)
        assertThat(result.summary.changed).isEqualTo(1)
        assertThat(result.summary.deleted).isEqualTo(0)
        assertThat(result.summary.unchanged).isEqualTo(3)
        assertThat(result.notes).isNotEmpty()

        val changed = result.lines.first { it.kind == SmartDiffKind.CHANGED }
        assertThat(changed.oldText).contains("original draft")
        assertThat(changed.newText).contains("revised draft")

        val inserted = result.lines.first { it.kind == SmartDiffKind.INSERTED }
        assertThat(inserted.text).startsWith("Appendix")
    }

    @Test
    @DisplayName("findSiblingVersions surfaces files with the same label and a different contentHash")
    fun siblingPairing() {
        val originalDoc = writeDocx(tmp.resolve("notes-original.docx"), listOf("Original notes"))
        val draftDoc = writeDocx(tmp.resolve("notes-draft.docx"), listOf("Original notes", "Draft addendum"))
        val identicalDoc = writeDocx(tmp.resolve("notes-identical.docx"), listOf("Original notes"))

        val original = persistFile(originalDoc, label = "notes.docx", contentHash = "h-orig".padEnd(64, '0'))
        val draft = persistFile(draftDoc, label = "notes.docx", contentHash = "h-draft".padEnd(64, '1'))
        // byte-identical copy: same label, same contentHash as `original` — must be excluded.
        persistFile(identicalDoc, label = "notes.docx", contentHash = original.contentHash!!)
        // different label entirely — must be excluded.
        persistFile(writeDocx(tmp.resolve("other.docx"), listOf("Unrelated")),
            label = "other.docx", contentHash = "h-other".padEnd(64, '2'))

        val siblings = smartDiffService.findSiblingVersions(original.id!!)
        assertThat(siblings.map { it.id }).containsExactly(draft.id)
    }

    @Test
    @DisplayName("diff throws when no strategy is registered for the resolved content-type")
    fun unsupportedContentTypeRejected() {
        val a = persistFile(
            tmp.resolve("a.txt").also { Files.writeString(it, "hello") },
            label = "a.txt",
            contentHash = "ha".padEnd(64, '0'),
            contentType = "text/plain",
        )
        val b = persistFile(
            tmp.resolve("b.txt").also { Files.writeString(it, "hello world") },
            label = "b.txt",
            contentHash = "hb".padEnd(64, '0'),
            contentType = "text/plain",
        )

        assertThat(
            runCatching { smartDiffService.diff(a.id!!, b.id!!) }
                .exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun writeDocx(path: Path, paragraphs: List<String>): Path {
        XWPFDocument().use { doc ->
            for (text in paragraphs) {
                doc.createParagraph().createRun().setText(text)
            }
            Files.newOutputStream(path).use { doc.write(it) }
        }
        return path
    }

    private fun persistFile(
        path: Path,
        label: String,
        contentHash: String,
        contentType: String = DocxSmartDiffStrategy.DOCX_CONTENT_TYPES.first(),
    ): FSFile {
        val entity = FSFile().apply {
            this.uri = path.toString()
            this.label = label
            this.type = "FILE"
            this.status = Status.CURRENT
            this.analysisStatus = AnalysisStatus.INDEX
            this.contentHash = contentHash
            this.contentType = contentType
            this.fsLastModified = OffsetDateTime.now()
            this.size = Files.size(path)
        }
        return fsFileRepository.save(entity)
    }
}
