package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.ContentChunkService
import com.oconeco.spring_search_tempo.base.FSFileService
import com.oconeco.spring_search_tempo.base.FSFolderService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import com.oconeco.spring_search_tempo.base.service.FSFileMapper
import com.oconeco.spring_search_tempo.base.service.FSFolderMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.item.Chunk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Issue #150 — re-ingest of a modified FSFile resets `nlpProcessedAt` on
 * its existing ContentChunks so the NLP step reprocesses them against the
 * new content.
 *
 * Exercises `CombinedCrawlWriter` end-to-end (wired with the real
 * `ContentChunkRepository`) rather than running the full `fsCrawlJob` —
 * the contract under test is the writer's reset behaviour, not the
 * surrounding batch plumbing.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@DisplayName("CombinedCrawlWriter resets stale NLP on FSFile re-ingest (issue #150)")
class CombinedCrawlWriterNlpResetIT : BaseIT() {

    @Autowired lateinit var folderService: FSFolderService
    @Autowired lateinit var fileService: FSFileService
    @Autowired lateinit var fsFolderRepository: FSFolderRepository
    @Autowired lateinit var fsFileRepository: FSFileRepository
    @Autowired lateinit var folderMapper: FSFolderMapper
    @Autowired lateinit var fileMapper: FSFileMapper
    @Autowired lateinit var contentChunkRepository: ContentChunkRepository
    @Suppress("unused") // chunkService autowired only to ensure the bean exists
    @Autowired lateinit var chunkService: ContentChunkService

    @Test
    @Transactional
    @DisplayName("re-ingest with newer fsLastModified nulls nlpProcessedAt on existing chunks")
    fun reingestResetsStaleNlp() {
        val baseTime = OffsetDateTime.parse("2026-06-01T00:00:00Z")
        val file = fsFileRepository.save(FSFile().apply {
            uri = "/it/issue-150/reingest.txt"
            label = "reingest.txt"
            status = Status.CURRENT
            analysisStatus = AnalysisStatus.ANALYZE
            fsLastModified = baseTime
            size = 100L
            version = 0L
        })
        // Pre-existing chunks with completed NLP — these are what get stale
        // when the file is edited.
        listOf(0, 1).forEach { n ->
            contentChunkRepository.save(ContentChunk().apply {
                concept = file
                text = "old chunk $n"
                chunkNumber = n
                chunkType = "Paragraph"
                nlpProcessedAt = baseTime
                sentiment = "POSITIVE"
                namedEntities = """[{"text":"Acme","type":"ORGANIZATION"}]"""
            })
        }

        // Simulate re-ingest: same id, newer fsLastModified, larger size.
        val reingestDto = FSFileDTO().apply {
            id = file.id
            uri = file.uri
            label = file.label
            type = "FILE"
            status = Status.CURRENT
            analysisStatus = AnalysisStatus.ANALYZE
            fsLastModified = baseTime.plusHours(1)
            size = 200L
            version = file.version
        }

        val writer = CombinedCrawlWriter(
            folderService = folderService,
            fileService = fileService,
            folderRepository = fsFolderRepository,
            fileRepository = fsFileRepository,
            folderMapper = folderMapper,
            fileMapper = fileMapper,
            checkpointService = null,
            contentChunkRepository = contentChunkRepository
        )
        writer.write(Chunk(CombinedCrawlResult(folder = null, files = listOf(reingestDto))))

        val chunks = contentChunkRepository.findByConceptIdOrderByChunkNumberAsc(file.id!!)
        assertThat(chunks)
            .describedAs("existing chunk rows must still be present")
            .hasSize(2)
        assertThat(chunks)
            .describedAs("every existing chunk's nlpProcessedAt must be reset")
            .allMatch { it.nlpProcessedAt == null }
        // Annotation columns are not touched — they will be overwritten by
        // the next NLPChunkProcessor run; keeping them avoids losing state
        // if the NLP step is delayed.
        assertThat(chunks)
            .describedAs("sentiment column should be left in place (overwritten by next NLP run)")
            .allMatch { it.sentiment == "POSITIVE" }

        val reloaded = fsFileRepository.findById(file.id!!).orElseThrow()
        assertThat(reloaded.fsLastModified)
            .describedAs("file row must reflect the new fsLastModified")
            .isEqualTo(baseTime.plusHours(1))
    }

    @Test
    @Transactional
    @DisplayName("re-ingest of unchanged file (same fsLastModified+size) does NOT reset NLP")
    fun reingestUnchangedDoesNotReset() {
        val baseTime = OffsetDateTime.parse("2026-06-01T00:00:00Z")
        val file = fsFileRepository.save(FSFile().apply {
            uri = "/it/issue-150/unchanged.txt"
            label = "unchanged.txt"
            status = Status.CURRENT
            analysisStatus = AnalysisStatus.ANALYZE
            fsLastModified = baseTime
            size = 100L
            version = 0L
        })
        contentChunkRepository.save(ContentChunk().apply {
            concept = file
            text = "chunk"
            chunkNumber = 0
            chunkType = "Paragraph"
            nlpProcessedAt = baseTime
        })

        // Same fsLastModified and size — e.g. LOCATE→INDEX promotion path
        // hitting the writer for the same physical content.
        val reingestDto = FSFileDTO().apply {
            id = file.id
            uri = file.uri
            label = file.label
            type = "FILE"
            status = Status.CURRENT
            analysisStatus = AnalysisStatus.INDEX
            fsLastModified = baseTime
            size = 100L
            version = file.version
        }

        val writer = CombinedCrawlWriter(
            folderService = folderService,
            fileService = fileService,
            folderRepository = fsFolderRepository,
            fileRepository = fsFileRepository,
            folderMapper = folderMapper,
            fileMapper = fileMapper,
            checkpointService = null,
            contentChunkRepository = contentChunkRepository
        )
        writer.write(Chunk(CombinedCrawlResult(folder = null, files = listOf(reingestDto))))

        val chunks = contentChunkRepository.findByConceptIdOrderByChunkNumberAsc(file.id!!)
        assertThat(chunks).hasSize(1)
        assertThat(chunks.first().nlpProcessedAt)
            .describedAs("unchanged-file re-ingest must leave nlpProcessedAt alone")
            .isEqualTo(baseTime)
    }
}
