package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.SmartDiffKind
import com.oconeco.spring_search_tempo.base.model.SmartDiffLine
import com.oconeco.spring_search_tempo.base.model.SmartDiffResult
import com.oconeco.spring_search_tempo.base.model.SmartDiffSection
import com.oconeco.spring_search_tempo.base.model.SmartDiffSummary
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.io.path.Path

/**
 * Smart-diff strategy for Microsoft PowerPoint `.pptx` files.
 *
 * Per-slide text extraction via POI's [XMLSlideShow] / [XSLFTextShape] and
 * per-slide text diff via the shared [LineDiffEmitter] (the same plumbing the
 * `.docx` strategy uses). Slides are pre-aligned so that a single edit inside
 * one slide doesn't blow up the diff when one deck has an inserted slide
 * ahead of the edit point.
 *
 * Spike: [docs/research/smart-diff-tools.md §3](../../../../../../../docs/research/smart-diff-tools.md).
 * Issue #145.
 *
 * ## Alignment heuristic
 *
 * The brief calls for "align slides by slide index first, fall back to title-similarity
 * matching for inserted/deleted slides." Concretely, the algorithm is a two-pointer
 * walk over the old and new slide lists with bounded title look-ahead:
 *
 * 1. **Position match.** If both slides at the current cursors have identical
 *    normalized titles (lowercased + collapsed whitespace) — including the common
 *    case where both are empty (untitled slides at the same position) — pair them
 *    and advance both cursors. Untitled slides are intentionally paired by
 *    position; we have no signal to do otherwise.
 * 2. **Title look-ahead.** If the current titles differ AND both sides have a
 *    non-empty title at the current cursor, look ahead up to [TITLE_LOOKAHEAD]
 *    slides on each side for a slide whose title matches the *other* side's
 *    current title.
 *    - A match in `new[j+1..]` means slides `new[j..foundIdx)` are insertions;
 *      emit them as INSERTED sections, then pair `old[i]` with `new[foundIdx]`.
 *    - A match in `old[i+1..]` means slides `old[i..foundIdx)` are deletions.
 *    - When both directions find a match, prefer the one with fewer slides to
 *      skip (closer match wins; ties favor INSERTED).
 * 3. **Fallback pairing.** If neither look-ahead finds a match, pair `old[i]`
 *    with `new[j]` as a CHANGED slide and advance both. This is the right call
 *    when slides have been retitled but otherwise correspond to each other.
 * 4. **Drain.** After exhausting one list, emit the remainder of the other as
 *    INSERTED or DELETED sections.
 *
 * Title normalization is intentionally lenient (case and whitespace only).
 * Bumping [TITLE_LOOKAHEAD] arbitrarily would catch more rearrangements but
 * also surface more false positives where two unrelated slides happen to share
 * a title (`"Q&A"`, `"Thank you"`); 10 covers the realistic insertion sizes.
 */
@Component
class PptxSmartDiffStrategy : SmartDiffStrategy {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun supportedContentTypes(): Set<String> = PPTX_CONTENT_TYPES

    override fun diff(oldFile: FSFile, newFile: FSFile): SmartDiffResult {
        val oldSlides = readSlides(oldFile)
        val newSlides = readSlides(newFile)
        val alignments = alignSlides(oldSlides, newSlides)

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
                is SlideAlignment.Paired -> {
                    val emitted = LineDiffEmitter.emit(alignment.old.diffLines, alignment.new.diffLines)
                    allLines += emitted.lines
                    totalInserted += emitted.summary.inserted
                    totalDeleted += emitted.summary.deleted
                    totalChanged += emitted.summary.changed
                    totalUnchanged += emitted.summary.unchanged
                    sectionKind = if (emitted.summary.totalChanges == 0) SmartDiffKind.UNCHANGED else SmartDiffKind.CHANGED
                    sectionKey = "slide-old-${alignment.old.index}-new-${alignment.new.index}"
                    sectionLabel = if (alignment.old.index == alignment.new.index) {
                        "Slide ${alignment.new.index}"
                    } else {
                        "Slide ${alignment.old.index} → ${alignment.new.index}"
                    }
                }
                is SlideAlignment.Inserted -> {
                    val lines = alignment.new.diffLines.mapIndexed { i, text ->
                        SmartDiffLine(
                            kind = SmartDiffKind.INSERTED,
                            newLineNo = i + 1,
                            text = text,
                        )
                    }
                    allLines += lines
                    totalInserted += lines.size
                    sectionKind = SmartDiffKind.INSERTED
                    sectionKey = "slide-new-${alignment.new.index}"
                    sectionLabel = "Slide ${alignment.new.index} [new]"
                }
                is SlideAlignment.Deleted -> {
                    val lines = alignment.old.diffLines.mapIndexed { i, text ->
                        SmartDiffLine(
                            kind = SmartDiffKind.DELETED,
                            oldLineNo = i + 1,
                            text = text,
                        )
                    }
                    allLines += lines
                    totalDeleted += lines.size
                    sectionKind = SmartDiffKind.DELETED
                    sectionKey = "slide-old-${alignment.old.index}"
                    sectionLabel = "Slide ${alignment.old.index} [removed]"
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

    private fun readSlides(file: FSFile): List<SlideContent> {
        val uri = file.uri
            ?: throw IllegalArgumentException("FSFile ${file.id} has no uri")
        val path = Path(uri)
        if (!Files.exists(path)) {
            throw NoSuchFileException(uri)
        }
        return Files.newInputStream(path).use { input ->
            XMLSlideShow(input).use { deck ->
                deck.slides.mapIndexed { idx, slide ->
                    val title = (slide.title ?: "").trim()
                    val body = buildList {
                        for (shape in slide.shapes) {
                            if (shape !is XSLFTextShape) continue
                            // Title is captured separately; skip its shape so we don't double-count.
                            if (shape.textType in TITLE_PLACEHOLDERS) continue
                            for (paragraph in shape.textParagraphs) {
                                val text = paragraph.text?.trim().orEmpty()
                                if (text.isNotEmpty()) add(text)
                            }
                        }
                    }
                    SlideContent(index = idx + 1, title = title, body = body)
                }
            }
        }.also {
            log.debug("Extracted {} slides from {}", it.size, file.uri)
        }
    }

    internal fun alignSlides(
        oldSlides: List<SlideContent>,
        newSlides: List<SlideContent>,
    ): List<SlideAlignment> {
        val out = mutableListOf<SlideAlignment>()
        var i = 0
        var j = 0
        while (i < oldSlides.size && j < newSlides.size) {
            val o = oldSlides[i]
            val n = newSlides[j]
            val oNorm = o.normalizedTitle
            val nNorm = n.normalizedTitle

            if (oNorm == nNorm) {
                out += SlideAlignment.Paired(o, n)
                i++; j++
                continue
            }

            val skipsInNew = if (oNorm.isNotEmpty()) {
                lookAhead(newSlides, j + 1, TITLE_LOOKAHEAD) { it.normalizedTitle == oNorm }
            } else -1
            val skipsInOld = if (nNorm.isNotEmpty()) {
                lookAhead(oldSlides, i + 1, TITLE_LOOKAHEAD) { it.normalizedTitle == nNorm }
            } else -1

            when {
                skipsInNew >= 0 && (skipsInOld < 0 || (skipsInNew - j) <= (skipsInOld - i)) -> {
                    while (j < skipsInNew) {
                        out += SlideAlignment.Inserted(newSlides[j])
                        j++
                    }
                }
                skipsInOld >= 0 -> {
                    while (i < skipsInOld) {
                        out += SlideAlignment.Deleted(oldSlides[i])
                        i++
                    }
                }
                else -> {
                    out += SlideAlignment.Paired(o, n)
                    i++; j++
                }
            }
        }
        while (i < oldSlides.size) {
            out += SlideAlignment.Deleted(oldSlides[i]); i++
        }
        while (j < newSlides.size) {
            out += SlideAlignment.Inserted(newSlides[j]); j++
        }
        return out
    }

    private fun lookAhead(
        slides: List<SlideContent>,
        start: Int,
        maxSkip: Int,
        predicate: (SlideContent) -> Boolean,
    ): Int {
        val end = minOf(slides.size, start + maxSkip)
        for (k in start until end) {
            if (predicate(slides[k])) return k
        }
        return -1
    }

    /**
     * Per-slide extracted content. [diffLines] is what the diff actually consumes — the
     * non-empty title (if any) followed by paragraph text from non-title shapes. The title
     * doubles as the alignment key in [normalizedTitle].
     */
    internal data class SlideContent(
        val index: Int,
        val title: String,
        val body: List<String>,
    ) {
        val diffLines: List<String> = if (title.isNotEmpty()) listOf(title) + body else body
        val normalizedTitle: String = title.lowercase().replace(WHITESPACE, " ").trim()

        companion object {
            private val WHITESPACE = Regex("\\s+")
        }
    }

    internal sealed interface SlideAlignment {
        data class Paired(val old: SlideContent, val new: SlideContent) : SlideAlignment
        data class Inserted(val new: SlideContent) : SlideAlignment
        data class Deleted(val old: SlideContent) : SlideAlignment
    }

    companion object {
        val PPTX_CONTENT_TYPES = setOf(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-powerpoint.presentation.macroenabled.12",
        )

        private const val TITLE_LOOKAHEAD = 10

        private val TITLE_PLACEHOLDERS = setOf(
            Placeholder.TITLE,
            Placeholder.CENTERED_TITLE,
        )

        private val NOTES = listOf(
            "Per-slide text only; embedded images, charts, and animations are ignored.",
            "Speaker notes are not included.",
            "Slides are aligned by index first; titles are used as anchors when slides are inserted or removed.",
        )
    }
}
