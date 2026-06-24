package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import com.oconeco.spring_search_tempo.batch.chunking.ChunkingStrategySelector
import com.oconeco.spring_search_tempo.batch.chunking.SentenceChunker
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.item.Chunk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * Issue #147 / ADR-006 acceptance test.
 *
 * Strategy A:
 * - The chunker sees the full ~5 MB extracted text (so chunks span every
 *   byte of the document, including a unique marker placed at offset
 *   4 MB).
 * - After chunks land, `fs_file.body_text` is truncated to
 *   `app.crawl.large-body-threshold-bytes` (50 KB in this test for speed)
 *   and a `…[truncated: N chars; full content in chunks]` marker is
 *   appended.
 * - The marker term is no longer present in `body_text` (it sits past
 *   byte 4 MB, well beyond the 50 KB cap), but it IS present in a
 *   `content_chunks` row and that row's `fts_vector` matches a
 *   `to_tsquery('english', 'marker')` query — i.e. FTS at offset 4 MB
 *   still finds the file via the chunk path.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("Large body chunking (ADR-006 / issue #147)")
class LargeBodyChunkingIT : BaseIT() {

    @Autowired private lateinit var fileRepository: FSFileRepository
    @Autowired private lateinit var fileService: FSFileService
    @Autowired private lateinit var fileMapper: FSFileMapper
    @Autowired private lateinit var chunkService: ContentChunkService
    @Autowired private lateinit var chunkRepository: ContentChunkRepository

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /** Threshold small enough that a 5 MB body is well past it but the
     *  test runs in seconds. The marker term is at byte ~4 MB, ensuring
     *  it lands far past this cap. */
    private val thresholdChars = 50_000L

    /** Pseudo-prose generator — repeating sentence templates so the
     *  sentence chunker has real boundaries to find. A unique marker
     *  string is spliced in at the requested offset. */
    private fun makeProse(totalChars: Int, markerOffset: Int, marker: String): String {
        val template = "The quick brown fox jumps over the lazy dog near the river bank. " +
            "Eager developers ship features while careful operators tune indices. "
        val sb = StringBuilder(totalChars + 64)
        while (sb.length < totalChars) sb.append(template)
        sb.setLength(totalChars)
        // Splice the marker as its own sentence so the chunker keeps it intact.
        val markerSentence = " ${marker.uppercase()} occurs exactly once. "
        val safeOffset = markerOffset.coerceIn(0, sb.length - markerSentence.length)
        sb.replace(safeOffset, safeOffset + markerSentence.length, markerSentence)
        return sb.toString()
    }

    private fun newLargeFile(uri: String, body: String): FSFile = FSFile().apply {
        this.uri = uri
        this.status = Status.NEW
        this.analysisStatus = AnalysisStatus.INDEX
        this.label = uri.substringAfterLast('/')
        this.type = "FILE"
        this.bodyText = body
        this.bodySize = body.length.toLong()
    }

    @Test
    @Transactional
    fun `5MB file gets chunked fully, body_text truncated, FTS at offset 4MB still finds it`() {
        val totalChars = 5 * 1024 * 1024
        val markerOffset = 4 * 1024 * 1024
        val marker = "zothuriumXYZ147"
        val body = makeProse(totalChars, markerOffset, marker)
        assertThat(body.length).isEqualTo(totalChars)
        assertThat(body.uppercase()).contains(marker.uppercase())

        val saved = fileRepository.save(newLargeFile("/tmp/test-147-large.txt", body))
        val fileId = saved.id!!
        entityManager.flush()

        // Wire up the chunk processor + writer exactly the same way
        // FsCrawlJobBuilder does — minus the batch Step / reader plumbing,
        // because we're feeding the DTO directly.
        val processor = ChunkProcessor(
            strategySelector = ChunkingStrategySelector(listOf(SentenceChunker()))
        )
        val writer = ChunkWriter(
            chunkService = chunkService,
            fileService = fileService,
            largeBodyThresholdChars = thresholdChars
        )

        val dto = fileMapper.updateFSFileDTO(saved, FSFileDTO())
        val chunks = processor.process(dto)
        assertThat(chunks).isNotNull
        assertThat(chunks!!).isNotEmpty
        writer.write(Chunk(listOf(chunks)))
        entityManager.flush()
        entityManager.clear()

        // -- Assertion 1: body_text is bounded per policy --
        val reloaded = fileRepository.findById(fileId).orElseThrow()
        val truncated = reloaded.bodyText!!
        assertThat(truncated.length).isLessThanOrEqualTo(thresholdChars.toInt() + 200)
        assertThat(truncated).contains("[truncated:")
        // Marker is past the threshold, so it must NOT survive in body_text.
        assertThat(truncated.uppercase()).doesNotContain(marker.uppercase())

        // -- Assertion 2: chunks cover the (effectively) full document --
        // SentenceChunker drops very short and pure-whitespace fragments;
        // an exact-byte sum is not the right invariant. The right test is
        // "the last chunk reaches the tail of the document, and the
        // combined chunk text length is within a tight fraction of the
        // original." 95% is well above what we see in practice.
        val persistedChunks = chunkRepository.findByConceptIdOrderByChunkNumberAsc(fileId)
        assertThat(persistedChunks).isNotEmpty
        val combinedLen = persistedChunks.sumOf { (it.text ?: "").length }
        assertThat(combinedLen)
            .describedAs("Chunks combined should cover ~all of the original text")
            .isGreaterThan((totalChars * 0.95).toInt())
        val maxEnd = persistedChunks.maxOf { it.endPosition ?: 0L }
        assertThat(maxEnd)
            .describedAs("Last chunk should reach the tail of the document")
            .isGreaterThan((totalChars * 0.95).toLong())

        // -- Assertion 3: FTS for the marker at byte-offset 4MB still hits via chunks --
        val markerInChunks = persistedChunks.any {
            (it.text ?: "").uppercase().contains(marker.uppercase())
        }
        assertThat(markerInChunks)
            .describedAs("Marker placed at byte-offset 4MB must land in at least one chunk")
            .isTrue()

        // GENERATED fts_vector column matches the marker via a native query.
        val markerTerm = marker.lowercase()
        val hits = entityManager.createNativeQuery(
            """
            SELECT COUNT(*)
            FROM content_chunks c
            WHERE c.concept_id = :fileId
              AND c.fts_vector @@ to_tsquery('english', :term)
            """.trimIndent()
        )
            .setParameter("fileId", fileId)
            .setParameter("term", markerTerm)
            .singleResult as Number
        assertThat(hits.toInt())
            .describedAs("content_chunks.fts_vector must match the marker for the 4MB-offset term")
            .isGreaterThan(0)
    }
}
