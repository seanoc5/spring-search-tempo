package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.domain.Status
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import org.assertj.core.api.Assertions.assertThat
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
 * Render coverage for the FSFile detail page's NLP section (issue #71).
 *
 * Verifies that sentiment badges and named-entity links are surfaced when chunks
 * have been NLP-processed, and that a "Not yet processed" placeholder appears
 * when they haven't.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("FSFile detail view renders NLP sentiment badges and entity links (issue #71)")
class FSFileViewNlpRenderIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var fSFileRepository: FSFileRepository

    @Autowired
    lateinit var contentChunkRepository: ContentChunkRepository

    @Test
    @DisplayName("GET /fSFiles/{id} — fully NLP-processed chunk renders sentiment badge + entity link")
    fun rendersBadgesAndEntityLinks() {
        val fileId = saveFile("nlp-processed.txt")
        // Text laid out so offsets line up with the entity spans below.
        val text = "Acme Corp launched in Seattle last Tuesday."
        //          0         1         2         3         4
        //          0123456789012345678901234567890123456789012
        //          ^Acme Corp^         ^Seattle^
        saveChunk(
            fileId = fileId,
            chunkNumber = 0,
            text = text,
            sentiment = "POSITIVE",
            sentimentScore = 0.87,
            namedEntities = """
                [
                  {"text":"Acme Corp","type":"ORGANIZATION","startOffset":0,"endOffset":9},
                  {"text":"Seattle","type":"LOCATION","startOffset":22,"endOffset":29}
                ]
            """.trimIndent(),
            nlpProcessedAt = OffsetDateTime.now(),
        )

        val body = mockMvc.perform(
            get("/fSFiles/{id}", fileId)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fSFile/view"))
            .andReturn().response.contentAsString

        assertThat(body).contains("id=\"nlp-analysis-card\"")
        assertThat(body).contains("nlp-sentiment-badge")
        assertThat(body).contains("bg-success") // POSITIVE → bg-success
        assertThat(body).contains("POSITIVE")
        assertThat(body).contains("nlp-entity-link")
        // Entity link → /search?q=...
        assertThat(body).contains("/search?q=Acme+Corp")
        assertThat(body).contains("/search?q=Seattle")
        // Entity type badge styling propagated through
        assertThat(body).contains("text-bg-success") // ORGANIZATION
        assertThat(body).contains("text-bg-info")    // LOCATION
        // No "Not yet processed" placeholder for this chunk
        assertThat(body).doesNotContain("nlp-not-processed")
    }

    @Test
    @DisplayName("GET /fSFiles/{id} — chunk with nlpProcessedAt=null shows 'Not yet processed' placeholder")
    fun rendersNotYetProcessedPlaceholder() {
        val fileId = saveFile("nlp-pending.txt")
        saveChunk(
            fileId = fileId,
            chunkNumber = 0,
            text = "This chunk has no NLP annotations yet.",
            sentiment = null,
            sentimentScore = null,
            namedEntities = null,
            nlpProcessedAt = null,
        )

        val body = mockMvc.perform(
            get("/fSFiles/{id}", fileId)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains("id=\"nlp-analysis-card\"")
        assertThat(body).contains("Not yet processed")
        assertThat(body).doesNotContain("nlp-sentiment-badge")
        assertThat(body).doesNotContain("nlp-entity-link")
    }

    @Test
    @DisplayName("GET /fSFiles/{id} — file with no chunks renders without an NLP card, no errors")
    fun rendersWithoutNlpCardWhenNoChunks() {
        val fileId = saveFile("no-chunks.txt")

        val body = mockMvc.perform(
            get("/fSFiles/{id}", fileId)
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).doesNotContain("id=\"nlp-analysis-card\"")
        assertThat(body).doesNotContain("nlp-sentiment-badge")
    }

    private fun saveFile(label: String): Long {
        val file = FSFile().apply {
            this.uri = "file:///tmp/${System.nanoTime()}-$label"
            this.label = label
            this.status = Status.CURRENT
            this.analysisStatus = AnalysisStatus.ANALYZE
            this.version = 0L
            this.bodyText = "stub body"
            this.bodySize = 9L
        }
        return fSFileRepository.save(file).id!!
    }

    private fun saveChunk(
        fileId: Long,
        chunkNumber: Int,
        text: String,
        sentiment: String?,
        sentimentScore: Double?,
        namedEntities: String?,
        nlpProcessedAt: OffsetDateTime?,
    ): Long {
        val file = fSFileRepository.findById(fileId).orElseThrow()
        val chunk = ContentChunk().apply {
            this.chunkNumber = chunkNumber
            this.chunkType = "Sentence"
            this.text = text
            this.sentiment = sentiment
            this.sentimentScore = sentimentScore
            this.namedEntities = namedEntities
            this.nlpProcessedAt = nlpProcessedAt
            this.concept = file
        }
        return contentChunkRepository.save(chunk).id!!
    }
}
