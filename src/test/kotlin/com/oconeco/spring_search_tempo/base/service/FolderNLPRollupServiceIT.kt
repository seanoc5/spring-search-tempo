package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.FSFolder
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import com.oconeco.spring_search_tempo.base.repos.FSFolderRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.time.OffsetDateTime

/**
 * Coverage for the folder NLP rollup (issue #151):
 * - [FolderNLPRollupService.getRollup] returns expected sentiment counts,
 *   weighted average sentiment score, last-NLP timestamp, and top entities
 *   grouped by entity type.
 * - The folder detail view renders the rollup panel when indexed files exist
 *   and suppresses it when they don't.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("Folder NLP rollup — DTO and rendered HTML (issue #151)")
class FolderNLPRollupServiceIT : BaseIT() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var folderNLPRollupService: FolderNLPRollupService
    @Autowired lateinit var fsFolderRepository: FSFolderRepository
    @Autowired lateinit var fsFileRepository: FSFileRepository
    @Autowired lateinit var contentChunkRepository: ContentChunkRepository

    @Test
    @DisplayName("getRollup aggregates sentiment, score, last-NLP timestamp, and top entities")
    fun aggregatesAcrossFolder() {
        val folder = saveFolder("/it/issue-151/aggregates")
        val file1 = saveFile(folder, "doc-a.txt", AnalysisStatus.ANALYZE)
        val file2 = saveFile(folder, "doc-b.txt", AnalysisStatus.ANALYZE)
        // Sibling-folder file that must NOT contribute to this folder's rollup.
        val otherFolder = saveFolder("/it/issue-151/other")
        val otherFile = saveFile(otherFolder, "noise.txt", AnalysisStatus.ANALYZE)

        val t0 = OffsetDateTime.parse("2026-01-01T10:00:00Z")
        val t1 = OffsetDateTime.parse("2026-01-02T10:00:00Z")
        val t2 = OffsetDateTime.parse("2026-01-03T10:00:00Z") // most recent

        saveChunk(file1, 0, sentiment = "POSITIVE", score = 0.8,
            entities = """[{"text":"Acme Corp","type":"ORGANIZATION"},{"text":"Seattle","type":"LOCATION"}]""",
            nlpAt = t0)
        saveChunk(file1, 1, sentiment = "POSITIVE", score = 0.9,
            entities = """[{"text":"Acme Corp","type":"ORGANIZATION"},{"text":"Alice","type":"PERSON"}]""",
            nlpAt = t1)
        saveChunk(file2, 0, sentiment = "NEGATIVE", score = 0.2,
            entities = """[{"text":"Bob","type":"PERSON"},{"text":"Seattle","type":"LOCATION"}]""",
            nlpAt = t2)
        saveChunk(file2, 1, sentiment = "NEUTRAL", score = 0.5,
            entities = """[{"text":"Acme Corp","type":"ORGANIZATION"}]""",
            nlpAt = t0)
        // Chunk under a file in a different folder — must be ignored entirely.
        saveChunk(otherFile, 0, sentiment = "POSITIVE", score = 1.0,
            entities = """[{"text":"Zigzag","type":"ORGANIZATION"}]""",
            nlpAt = t2)

        val rollup = folderNLPRollupService.getRollup(folder.id!!)

        assertThat(rollup.folderId).isEqualTo(folder.id)
        assertThat(rollup.indexedFileCount).isEqualTo(2L)
        assertThat(rollup.totalChunkCount).isEqualTo(4L)
        assertThat(rollup.sentimentCounts)
            .containsEntry("POSITIVE", 2L)
            .containsEntry("NEUTRAL", 1L)
            .containsEntry("NEGATIVE", 1L)
        // Weighted avg of (0.8, 0.9, 0.2, 0.5) per chunk = 2.4/4 = 0.6
        assertThat(rollup.averageSentimentScore).isNotNull()
        assertThat(rollup.averageSentimentScore!!).isCloseTo(0.6, within(0.0001))
        assertThat(rollup.lastNlpProcessedAt).isEqualTo(t2)

        // Top entities grouped by type, excluding the sibling folder's "Zigzag".
        val orgs = rollup.topEntitiesByType["ORGANIZATION"].orEmpty()
        assertThat(orgs.map { it.text }).containsExactly("Acme Corp")
        assertThat(orgs.single().count).isEqualTo(3L)
        assertThat(orgs.map { it.text }).doesNotContain("Zigzag")

        val locations = rollup.topEntitiesByType["LOCATION"].orEmpty()
        assertThat(locations.map { it.text }).containsExactly("Seattle")
        assertThat(locations.single().count).isEqualTo(2L)

        val persons = rollup.topEntitiesByType["PERSON"].orEmpty()
        assertThat(persons.map { it.text }).containsExactlyInAnyOrder("Alice", "Bob")
        assertThat(persons.map { it.count }).containsOnly(1L)
    }

    @Test
    @DisplayName("getRollup returns empty DTO when folder has no INDEX/ANALYZE/SEMANTIC files")
    fun emptyWhenNoIndexedFiles() {
        val folder = saveFolder("/it/issue-151/empty")
        // LOCATE files don't count as indexed — should leave the rollup empty.
        saveFile(folder, "metadata-only.txt", AnalysisStatus.LOCATE)

        val rollup = folderNLPRollupService.getRollup(folder.id!!)

        assertThat(rollup.isEmpty()).isTrue()
        assertThat(rollup.indexedFileCount).isZero()
        assertThat(rollup.totalChunkCount).isZero()
        assertThat(rollup.sentimentCounts).isEmpty()
        assertThat(rollup.topEntitiesByType).isEmpty()
    }

    @Test
    @DisplayName("GET /fSFolders/{id} renders rollup panel with sentiment bar + entity links")
    fun rendersPanelOnFolderDetailView() {
        val folder = saveFolder("/it/issue-151/render")
        val file = saveFile(folder, "report.txt", AnalysisStatus.ANALYZE)
        saveChunk(file, 0, sentiment = "POSITIVE", score = 0.75,
            entities = """[{"text":"Acme Corp","type":"ORGANIZATION"}]""",
            nlpAt = OffsetDateTime.parse("2026-01-15T12:00:00Z"))

        val body = mockMvc.perform(
            get("/fSFolders/{id}", folder.id!!)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fSFolder/view"))
            .andReturn().response.contentAsString

        assertThat(body).contains("folder-nlp-rollup")
        assertThat(body).contains("folder-nlp-sentiment-bar")
        // POSITIVE bar should be present
        assertThat(body).contains("bg-success")
        // Entity link routes to /search with entityTypes filter (& is HTML-encoded as &amp;)
        assertThat(body).contains("/search?q=Acme%20Corp&amp;entityTypes=ORGANIZATION")
        assertThat(body).contains("ORGANIZATION")
        assertThat(body).contains("Acme Corp")
    }

    @Test
    @DisplayName("GET /fSFolders/{id} omits rollup panel when folder has no indexed files")
    fun omitsPanelWhenEmpty() {
        val folder = saveFolder("/it/issue-151/render-empty")

        val body = mockMvc.perform(
            get("/fSFolders/{id}", folder.id!!)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("folder-nlp-rollup")
        assertThat(body).doesNotContain("folder-nlp-sentiment-bar")
    }

    private fun saveFolder(uri: String): FSFolder {
        val folder = FSFolder().apply {
            this.uri = uri
            this.label = uri.substringAfterLast('/')
            this.status = Status.CURRENT
            this.analysisStatus = AnalysisStatus.ANALYZE
            this.version = 0L
        }
        return fsFolderRepository.save(folder)
    }

    private fun saveFile(folder: FSFolder, name: String, analysisStatus: AnalysisStatus): FSFile {
        val file = FSFile().apply {
            this.uri = "${folder.uri}/$name"
            this.label = name
            this.status = Status.CURRENT
            this.analysisStatus = analysisStatus
            this.version = 0L
            this.fsFolder = folder
        }
        return fsFileRepository.save(file)
    }

    private fun saveChunk(
        file: FSFile,
        chunkNumber: Int,
        sentiment: String?,
        score: Double?,
        entities: String?,
        nlpAt: OffsetDateTime?,
    ): ContentChunk {
        val chunk = ContentChunk().apply {
            this.concept = file
            this.text = "Chunk $chunkNumber of ${file.label}"
            this.chunkNumber = chunkNumber
            this.chunkType = "Sentence"
            this.sentiment = sentiment
            this.sentimentScore = score
            this.namedEntities = entities
            this.nlpProcessedAt = nlpAt
        }
        return contentChunkRepository.save(chunk)
    }
}
