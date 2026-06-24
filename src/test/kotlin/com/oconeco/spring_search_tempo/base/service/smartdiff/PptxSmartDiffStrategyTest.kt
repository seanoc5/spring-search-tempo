package com.oconeco.spring_search_tempo.base.service.smartdiff

import com.oconeco.spring_search_tempo.base.model.SmartDiffKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Strategy-level coverage for [PptxSmartDiffStrategy] — focuses on the
 * slide-alignment heuristic since that's the only thing this strategy adds
 * beyond the shared [LineDiffEmitter] (issue #145). End-to-end POI
 * extraction is covered by the IT.
 */
@DisplayName("PptxSmartDiffStrategy (issue #145)")
class PptxSmartDiffStrategyTest {

    private val strategy = PptxSmartDiffStrategy()

    @Nested
    @DisplayName("alignSlides()")
    inner class AlignSlides {

        @Test
        @DisplayName("identical titled decks pair every slide by position")
        fun identicalDecks() {
            val deck = listOf(
                slide(1, "Intro", listOf("hello")),
                slide(2, "Body", listOf("middle")),
                slide(3, "Outro", listOf("bye")),
            )
            val alignments = strategy.alignSlides(deck, deck)
            assertThat(alignments).hasSize(3)
                .allSatisfy {
                    assertThat(it).isInstanceOf(PptxSmartDiffStrategy.SlideAlignment.Paired::class.java)
                }
        }

        @Test
        @DisplayName("inserted slide between two unchanged: middle slide reported as Inserted")
        fun insertedSlideBetweenUnchanged() {
            val old = listOf(
                slide(1, "Intro", listOf("hello")),
                slide(2, "Outro", listOf("bye")),
            )
            val new = listOf(
                slide(1, "Intro", listOf("hello")),
                slide(2, "New middle", listOf("middle content")),
                slide(3, "Outro", listOf("bye")),
            )
            val alignments = strategy.alignSlides(old, new)
            assertThat(alignments).hasSize(3)
            assertThat(alignments[0]).isInstanceOf(PptxSmartDiffStrategy.SlideAlignment.Paired::class.java)
            assertThat(alignments[1]).isInstanceOfSatisfying(
                PptxSmartDiffStrategy.SlideAlignment.Inserted::class.java
            ) { ins ->
                assertThat(ins.new.title).isEqualTo("New middle")
            }
            assertThat(alignments[2]).isInstanceOfSatisfying(
                PptxSmartDiffStrategy.SlideAlignment.Paired::class.java
            ) { p ->
                assertThat(p.old.title).isEqualTo("Outro")
                assertThat(p.new.title).isEqualTo("Outro")
            }
        }

        @Test
        @DisplayName("deleted slide: middle slide present in old but absent in new")
        fun deletedSlide() {
            val old = listOf(
                slide(1, "Intro", listOf("hello")),
                slide(2, "Removed", listOf("gone")),
                slide(3, "Outro", listOf("bye")),
            )
            val new = listOf(
                slide(1, "Intro", listOf("hello")),
                slide(2, "Outro", listOf("bye")),
            )
            val alignments = strategy.alignSlides(old, new)
            assertThat(alignments).hasSize(3)
            assertThat(alignments[1]).isInstanceOfSatisfying(
                PptxSmartDiffStrategy.SlideAlignment.Deleted::class.java
            ) { d ->
                assertThat(d.old.title).isEqualTo("Removed")
            }
        }

        @Test
        @DisplayName("untitled slides at the same position pair without title look-ahead")
        fun untitledSlidesPairByPosition() {
            val old = listOf(
                slide(1, "", listOf("a")),
                slide(2, "", listOf("b")),
            )
            val new = listOf(
                slide(1, "", listOf("a")),
                slide(2, "", listOf("b CHANGED")),
            )
            val alignments = strategy.alignSlides(old, new)
            assertThat(alignments).hasSize(2)
                .allSatisfy {
                    assertThat(it).isInstanceOf(PptxSmartDiffStrategy.SlideAlignment.Paired::class.java)
                }
        }

        @Test
        @DisplayName("title retitled with no nearby match: pair as CHANGED (fallback)")
        fun retitledSlidePairsAsChanged() {
            val old = listOf(slide(1, "Old title", listOf("body")))
            val new = listOf(slide(1, "New title", listOf("body")))
            val alignments = strategy.alignSlides(old, new)
            assertThat(alignments).hasSize(1)
            assertThat(alignments[0]).isInstanceOf(PptxSmartDiffStrategy.SlideAlignment.Paired::class.java)
        }

        @Test
        @DisplayName("trailing extra slides in new deck are all Inserted")
        fun trailingInsertsDrained() {
            val old = listOf(slide(1, "A", emptyList()))
            val new = listOf(
                slide(1, "A", emptyList()),
                slide(2, "B", emptyList()),
                slide(3, "C", emptyList()),
            )
            val alignments = strategy.alignSlides(old, new)
            assertThat(alignments).hasSize(3)
            assertThat(alignments.drop(1)).allSatisfy {
                assertThat(it).isInstanceOf(PptxSmartDiffStrategy.SlideAlignment.Inserted::class.java)
            }
        }
    }

    @Nested
    @DisplayName("LineDiffEmitter is the same plumbing as .docx")
    inner class SharedEmitter {

        @Test
        @DisplayName("identical streams produce all-UNCHANGED with zero changes")
        fun identicalStreams() {
            val out = LineDiffEmitter.emit(listOf("alpha", "beta"), listOf("alpha", "beta"))
            assertThat(out.summary.totalChanges).isZero
            assertThat(out.summary.unchanged).isEqualTo(2)
            assertThat(out.lines).allSatisfy {
                assertThat(it.kind).isEqualTo(SmartDiffKind.UNCHANGED)
            }
        }

        @Test
        @DisplayName("changed delta with more new lines spills excess into INSERTED (shared with docx)")
        fun changeSpillsToInsert() {
            val out = LineDiffEmitter.emit(
                listOf("first", "old line"),
                listOf("first", "new line a", "new line b"),
            )
            assertThat(out.summary.changed).isEqualTo(1)
            assertThat(out.summary.inserted).isEqualTo(1)
            assertThat(out.summary.deleted).isEqualTo(0)
            assertThat(out.summary.unchanged).isEqualTo(1)
        }
    }

    private fun slide(index: Int, title: String, body: List<String>) =
        PptxSmartDiffStrategy.SlideContent(index = index, title = title, body = body)
}
