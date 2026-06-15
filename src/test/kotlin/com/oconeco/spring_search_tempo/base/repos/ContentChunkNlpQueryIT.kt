package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.Status
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest

/**
 * Regression IT for issue #130.
 *
 * Locks in that the NLP / embedding eligibility queries on
 * [ContentChunkRepository] return chunks whose only populated parent is
 * one of `concept`, `emailMessage`, `browserBookmark`, or `oneDriveItem`.
 *
 * Before the fix these queries used JPA path expressions
 * (`c.concept.analysisStatus`, etc.) chained with `OR`. Hibernate generated
 * implicit INNER joins for each path, producing a four-way cartesian where
 * a candidate row needed all four parent FKs populated. Single-parent
 * chunks (the common case) silently dropped out — the NLP step's
 * `RepositoryItemReader` saw zero rows.
 *
 * These assertions exercise the FSFile parent path specifically — that's
 * the production hot path and the one that surfaced the bug. The fix
 * (explicit `LEFT JOIN`s on each parent) makes the queries correct for
 * all four parent types because they share the same query shape.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("ContentChunkRepository NLP/embedding queries return single-parent chunks (issue #130)")
class ContentChunkNlpQueryIT : BaseIT() {

    @Autowired
    lateinit var fsFileRepository: FSFileRepository

    @Autowired
    lateinit var contentChunkRepository: ContentChunkRepository

    @Test
    @DisplayName("findChunksForNlpProcessing returns FSFile-only chunks at ANALYZE")
    fun findChunksForNlpProcessingReturnsFSFileOnlyChunks() {
        val analyzeFile = saveFile("/it/issue-130/analyze.txt", AnalysisStatus.ANALYZE)
        val indexFile = saveFile("/it/issue-130/index.txt", AnalysisStatus.INDEX)

        // Two pending chunks under the ANALYZE file (should be picked up).
        saveChunk(analyzeFile, text = "ANALYZE chunk 1", chunkNumber = 0, nlpProcessed = false)
        saveChunk(analyzeFile, text = "ANALYZE chunk 2", chunkNumber = 1, nlpProcessed = false)
        // One already-processed chunk under ANALYZE (must NOT be returned).
        saveChunk(analyzeFile, text = "ANALYZE chunk 3", chunkNumber = 2, nlpProcessed = true)
        // One pending chunk under INDEX (parent not eligible — must NOT be returned).
        saveChunk(indexFile, text = "INDEX chunk 1", chunkNumber = 0, nlpProcessed = false)

        val page = contentChunkRepository.findChunksForNlpProcessing(
            analysisStatuses = listOf(AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC),
            pageable = PageRequest.of(0, 50),
        )

        assertThat(page.totalElements)
            .describedAs("two pending ANALYZE chunks should be eligible")
            .isEqualTo(2L)
        assertThat(page.content.map { it.text })
            .containsExactlyInAnyOrder("ANALYZE chunk 1", "ANALYZE chunk 2")
    }

    @Test
    @DisplayName("countByParentAnalysisStatus counts FSFile-only chunks")
    fun countByParentAnalysisStatusCountsFSFileOnlyChunks() {
        val analyzeFile = saveFile("/it/issue-130/count-analyze.txt", AnalysisStatus.ANALYZE)
        val indexFile = saveFile("/it/issue-130/count-index.txt", AnalysisStatus.INDEX)
        saveChunk(analyzeFile, text = "a1", chunkNumber = 0)
        saveChunk(analyzeFile, text = "a2", chunkNumber = 1)
        saveChunk(indexFile, text = "i1", chunkNumber = 0)

        assertThat(contentChunkRepository.countByParentAnalysisStatus(AnalysisStatus.ANALYZE))
            .isEqualTo(2L)
        assertThat(contentChunkRepository.countByParentAnalysisStatus(AnalysisStatus.INDEX))
            .isEqualTo(1L)
        assertThat(contentChunkRepository.countByParentAnalysisStatus(AnalysisStatus.LOCATE))
            .isEqualTo(0L)
    }

    @Test
    @DisplayName("countNlpPendingAtAnalyzeLevel counts only un-processed ANALYZE/SEMANTIC chunks")
    fun countNlpPendingAtAnalyzeLevelCountsPendingOnly() {
        val analyzeFile = saveFile("/it/issue-130/pending-analyze.txt", AnalysisStatus.ANALYZE)
        val indexFile = saveFile("/it/issue-130/pending-index.txt", AnalysisStatus.INDEX)
        // 2 pending + 1 processed under ANALYZE
        saveChunk(analyzeFile, text = "pending-a1", chunkNumber = 0, nlpProcessed = false)
        saveChunk(analyzeFile, text = "pending-a2", chunkNumber = 1, nlpProcessed = false)
        saveChunk(analyzeFile, text = "done-a3", chunkNumber = 2, nlpProcessed = true)
        // 1 pending under INDEX (must not count toward analyze-level pending)
        saveChunk(indexFile, text = "pending-i1", chunkNumber = 0, nlpProcessed = false)

        val pending = contentChunkRepository.countNlpPendingAtAnalyzeLevel(
            listOf(AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC),
        )
        assertThat(pending).isEqualTo(2L)
    }

    @Test
    @DisplayName("findChunksForEmbedding returns NLP-processed FSFile-only chunks at ANALYZE")
    fun findChunksForEmbeddingReturnsEligibleChunks() {
        val analyzeFile = saveFile("/it/issue-130/embed-analyze.txt", AnalysisStatus.ANALYZE)
        val indexFile = saveFile("/it/issue-130/embed-index.txt", AnalysisStatus.INDEX)
        // Eligible: NLP-processed, no embedding yet, ANALYZE parent.
        saveChunk(analyzeFile, text = "embed-1", chunkNumber = 0, nlpProcessed = true)
        saveChunk(analyzeFile, text = "embed-2", chunkNumber = 1, nlpProcessed = true)
        // Not eligible: NLP not yet processed.
        saveChunk(analyzeFile, text = "embed-3", chunkNumber = 2, nlpProcessed = false)
        // Not eligible: parent at INDEX, not ANALYZE/SEMANTIC.
        saveChunk(indexFile, text = "embed-i1", chunkNumber = 0, nlpProcessed = true)

        val page = contentChunkRepository.findChunksForEmbedding(
            forceRefresh = false,
            analysisStatuses = listOf(AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC),
            pageable = PageRequest.of(0, 50),
        )

        assertThat(page.totalElements).isEqualTo(2L)
        assertThat(page.content.map { it.text })
            .containsExactlyInAnyOrder("embed-1", "embed-2")
    }

    private fun saveFile(uri: String, analysisStatus: AnalysisStatus): FSFile {
        val file = FSFile().apply {
            this.uri = uri
            this.label = uri.substringAfterLast('/')
            this.status = Status.CURRENT
            this.analysisStatus = analysisStatus
            this.version = 0L
        }
        return fsFileRepository.save(file)
    }

    private fun saveChunk(
        file: FSFile,
        text: String,
        chunkNumber: Int,
        nlpProcessed: Boolean = false,
    ): ContentChunk {
        val chunk = ContentChunk().apply {
            this.concept = file
            this.text = text
            this.chunkNumber = chunkNumber
            this.chunkType = "Paragraph"
            if (nlpProcessed) {
                this.nlpProcessedAt = java.time.OffsetDateTime.now()
            }
        }
        return contentChunkRepository.save(chunk)
    }
}
