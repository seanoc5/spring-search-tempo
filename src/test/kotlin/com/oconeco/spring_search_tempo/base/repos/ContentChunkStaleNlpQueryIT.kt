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
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Issue #150 — detection + reset of stale NLP annotations on ContentChunk.
 *
 * Locks in the two repository hooks the swarm-fix added:
 *   1. `findStaleNlpChunks` returns chunks whose `nlpProcessedAt` lags the
 *      parent FSFile's `fsLastModified` (or is null).
 *   2. `resetNlpProcessedAtByConceptId` nulls `nlpProcessedAt` on every
 *      chunk for a given file so the standard NLP reader picks them up on
 *      the next run.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("ContentChunkRepository stale-NLP detection + reset (issue #150)")
class ContentChunkStaleNlpQueryIT : BaseIT() {

    @Autowired lateinit var fsFileRepository: FSFileRepository
    @Autowired lateinit var contentChunkRepository: ContentChunkRepository

    @Test
    @DisplayName("findStaleNlpChunks: chunk nlpProcessedAt < parent fsLastModified is stale")
    fun staleWhenNlpOlderThanParent() {
        val baseTime = OffsetDateTime.parse("2026-06-01T00:00:00Z")
        val file = saveFile(
            uri = "/it/issue-150/edited.txt",
            fsLastModified = baseTime.plusHours(2), // file edited later
        )
        // NLP ran before the file was edited → stale
        saveChunk(file, text = "stale", chunkNumber = 0, nlpProcessedAt = baseTime)
        // NLP ran after file edit → fresh
        saveChunk(file, text = "fresh", chunkNumber = 1, nlpProcessedAt = baseTime.plusHours(3))

        val stale = contentChunkRepository.findStaleNlpChunks(PageRequest.of(0, 50))

        assertThat(stale.content.map { it.text })
            .containsExactly("stale")
    }

    @Test
    @DisplayName("findStaleNlpChunks: nlpProcessedAt = null counts as stale")
    fun staleWhenNlpNeverRan() {
        val file = saveFile(
            uri = "/it/issue-150/never-nlpd.txt",
            fsLastModified = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
        )
        saveChunk(file, text = "pending", chunkNumber = 0, nlpProcessedAt = null)

        val stale = contentChunkRepository.findStaleNlpChunks(PageRequest.of(0, 50))

        assertThat(stale.content.map { it.text }).containsExactly("pending")
    }

    @Test
    @Transactional
    @DisplayName("resetNlpProcessedAtByConceptId nulls nlpProcessedAt for all chunks of a file")
    fun resetByConceptIdNullsAll() {
        val baseTime = OffsetDateTime.parse("2026-06-01T00:00:00Z")
        val targetFile = saveFile(uri = "/it/issue-150/reset-target.txt", fsLastModified = baseTime)
        val otherFile = saveFile(uri = "/it/issue-150/reset-other.txt", fsLastModified = baseTime)
        saveChunk(targetFile, text = "t1", chunkNumber = 0, nlpProcessedAt = baseTime)
        saveChunk(targetFile, text = "t2", chunkNumber = 1, nlpProcessedAt = baseTime)
        val untouchedId = saveChunk(otherFile, text = "o1", chunkNumber = 0, nlpProcessedAt = baseTime).id!!

        val updated = contentChunkRepository.resetNlpProcessedAtByConceptId(targetFile.id!!)

        assertThat(updated).isEqualTo(2)
        val targetChunks = contentChunkRepository.findByConceptIdOrderByChunkNumberAsc(targetFile.id!!)
        assertThat(targetChunks).allMatch { it.nlpProcessedAt == null }
        // Sibling file untouched.
        val sibling = contentChunkRepository.findById(untouchedId).orElseThrow()
        assertThat(sibling.nlpProcessedAt).isNotNull()
    }

    private fun saveFile(uri: String, fsLastModified: OffsetDateTime?): FSFile {
        val file = FSFile().apply {
            this.uri = uri
            this.label = uri.substringAfterLast('/')
            this.status = Status.CURRENT
            this.analysisStatus = AnalysisStatus.ANALYZE
            this.fsLastModified = fsLastModified
            this.version = 0L
        }
        return fsFileRepository.save(file)
    }

    private fun saveChunk(
        file: FSFile,
        text: String,
        chunkNumber: Int,
        nlpProcessedAt: OffsetDateTime?,
    ): ContentChunk {
        val chunk = ContentChunk().apply {
            this.concept = file
            this.text = text
            this.chunkNumber = chunkNumber
            this.chunkType = "Paragraph"
            this.nlpProcessedAt = nlpProcessedAt
        }
        return contentChunkRepository.save(chunk)
    }
}
