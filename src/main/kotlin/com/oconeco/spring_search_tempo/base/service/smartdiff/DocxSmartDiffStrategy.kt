package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.SmartDiffResult
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
        val emitted = LineDiffEmitter.emit(oldParagraphs, newParagraphs)
        return SmartDiffResult(
            oldFileId = oldFile.id!!,
            newFileId = newFile.id!!,
            oldLabel = oldFile.label,
            newLabel = newFile.label,
            contentType = newFile.contentType ?: oldFile.contentType,
            lines = emitted.lines,
            summary = emitted.summary,
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
