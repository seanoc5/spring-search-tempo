package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.SmartDiffService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.SmartDiffKind
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.xslf.usermodel.XMLSlideShow
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
 * Issue #145: .pptx smart-diff strategy on the shared infrastructure.
 *
 * Round-trip through the full Spring stack: register the strategy via DI,
 * persist two FSFile rows pointing at on-disk `.pptx` files, and assert that
 * `SmartDiffService.diff(...)` returns per-slide-aligned changes.
 *
 * Fixture (i): same deck, one slide's text edited.
 * Fixture (ii): one slide inserted between two unchanged slides.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("SmartDiffService + PptxSmartDiffStrategy (issue #145)")
class PptxSmartDiffStrategyIT : BaseIT() {

    @Autowired
    lateinit var smartDiffService: SmartDiffService

    @Autowired
    lateinit var fsFileRepository: FSFileRepository

    @TempDir
    lateinit var tmp: Path

    @Test
    @DisplayName("pptx strategy is registered for the canonical presentationML content-type")
    fun strategyRegistered() {
        assertThat(
            smartDiffService.isSupported(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            )
        ).isTrue()
    }

    @Test
    @DisplayName("fixture (i): single-slide text edit reports one CHANGED section, others UNCHANGED")
    fun singleSlideEdit() {
        val oldDeck = writePptx(
            tmp.resolve("deck-v1.pptx"),
            listOf(
                "Intro" to listOf("Welcome to the deck", "Agenda overview"),
                "Body" to listOf("Original body line one", "Original body line two"),
                "Outro" to listOf("Thanks for watching"),
            ),
        )
        val newDeck = writePptx(
            tmp.resolve("deck-v2.pptx"),
            listOf(
                "Intro" to listOf("Welcome to the deck", "Agenda overview"),
                "Body" to listOf("Revised body line one", "Original body line two"),
                "Outro" to listOf("Thanks for watching"),
            ),
        )

        val oldFile = persistFile(oldDeck, "deck.pptx", "old-hash-".padEnd(64, '0'))
        val newFile = persistFile(newDeck, "deck.pptx", "new-hash-".padEnd(64, '0'))

        val result = smartDiffService.diff(oldFileId = oldFile.id!!, newFileId = newFile.id!!)

        assertThat(result.sections).hasSize(3)
        assertThat(result.sections.map { it.label })
            .containsExactly("Slide 1", "Slide 2", "Slide 3")
        assertThat(result.sections.map { it.kind })
            .containsExactly(SmartDiffKind.UNCHANGED, SmartDiffKind.CHANGED, SmartDiffKind.UNCHANGED)
        assertThat(result.summary.changed + result.summary.inserted + result.summary.deleted)
            .isGreaterThan(0)

        // The CHANGED section must contain at least one line citing the revision.
        val bodySection = result.sections[1]
        val bodyLines = result.lines.subList(bodySection.lineStartIndex, bodySection.lineEndIndex)
        assertThat(bodyLines).anySatisfy {
            assertThat(it.oldText ?: it.text ?: "").contains("Original body line one")
        }
        assertThat(bodyLines).anySatisfy {
            assertThat(it.newText ?: it.text ?: "").contains("Revised body line one")
        }
    }

    @Test
    @DisplayName("fixture (ii): inserted middle slide produces an INSERTED section between two UNCHANGED ones")
    fun insertedSlide() {
        val oldDeck = writePptx(
            tmp.resolve("deck-v1.pptx"),
            listOf(
                "Intro" to listOf("Hello"),
                "Outro" to listOf("Bye"),
            ),
        )
        val newDeck = writePptx(
            tmp.resolve("deck-v2.pptx"),
            listOf(
                "Intro" to listOf("Hello"),
                "Middle" to listOf("Brand new slide content"),
                "Outro" to listOf("Bye"),
            ),
        )

        val oldFile = persistFile(oldDeck, "deck.pptx", "old-hash-".padEnd(64, '1'))
        val newFile = persistFile(newDeck, "deck.pptx", "new-hash-".padEnd(64, '1'))

        val result = smartDiffService.diff(oldFileId = oldFile.id!!, newFileId = newFile.id!!)

        assertThat(result.sections).hasSize(3)
        assertThat(result.sections.map { it.kind })
            .containsExactly(SmartDiffKind.UNCHANGED, SmartDiffKind.INSERTED, SmartDiffKind.UNCHANGED)
        assertThat(result.sections[1].label).contains("[new]")
        assertThat(result.summary.inserted).isGreaterThan(0)

        // The INSERTED section's lines must all be INSERTED, and they must include the new content.
        val insertedSection = result.sections[1]
        val insertedLines = result.lines.subList(insertedSection.lineStartIndex, insertedSection.lineEndIndex)
        assertThat(insertedLines).isNotEmpty
        assertThat(insertedLines).allSatisfy {
            assertThat(it.kind).isEqualTo(SmartDiffKind.INSERTED)
        }
        assertThat(insertedLines.map { it.text }).anyMatch { it != null && it.contains("Brand new slide content") }
    }

    private fun writePptx(path: Path, slides: List<Pair<String, List<String>>>): Path {
        XMLSlideShow().use { deck ->
            for ((title, body) in slides) {
                val slide = deck.createSlide()
                val titleShape = slide.createTextBox()
                titleShape.placeholder = Placeholder.TITLE
                titleShape.text = title

                if (body.isNotEmpty()) {
                    val bodyShape = slide.createTextBox()
                    bodyShape.text = body.joinToString("\n")
                }
            }
            Files.newOutputStream(path).use { deck.write(it) }
        }
        return path
    }

    private fun persistFile(
        path: Path,
        label: String,
        contentHash: String,
        contentType: String = PptxSmartDiffStrategy.PPTX_CONTENT_TYPES.first(),
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
