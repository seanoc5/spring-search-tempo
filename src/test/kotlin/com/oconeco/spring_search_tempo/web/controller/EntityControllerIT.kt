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
 * Renders the dedicated entity-detail / entity-search route added in issue #81.
 *
 * The FSFile NLP section links each named entity here with both its text *and*
 * its NER type so e.g. "Seattle" (LOCATION) doesn't collide with the common
 * word in plain FTS.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("GET /entity renders chunks aggregated by named entity (issue #81)")
class EntityControllerIT : BaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var fSFileRepository: FSFileRepository

    @Autowired
    lateinit var contentChunkRepository: ContentChunkRepository

    @Test
    @DisplayName("returns chunks tagged with the requested entity text + type")
    fun matchesByTextAndType() {
        val fileId = saveFile("seattle-doc.txt")
        saveChunk(
            fileId = fileId,
            chunkNumber = 0,
            text = "Acme Corp launched in Seattle last Tuesday.",
            namedEntities = """
                [
                  {"text":"Acme Corp","type":"ORGANIZATION","startOffset":0,"endOffset":9},
                  {"text":"Seattle","type":"LOCATION","startOffset":22,"endOffset":29}
                ]
            """.trimIndent(),
        )

        val body = mockMvc.perform(
            get("/entity")
                .param("text", "Seattle")
                .param("type", "LOCATION")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andExpect(view().name("entity/detail"))
            .andReturn().response.contentAsString

        // Page header shows the entity + type badge
        assertThat(body).contains("Seattle")
        assertThat(body).contains("LOCATION")
        // Matched chunk shows up
        assertThat(body).contains("Acme Corp launched in Seattle")
        // Co-occurring entity link points back at /entity, not /search
        assertThat(body).contains("/entity?text=Seattle")
    }

    @Test
    @DisplayName("ignores type filter that's not in VALID_ENTITY_TYPES and warns the user")
    fun ignoresUnknownTypeAndWarns() {
        val body = mockMvc.perform(
            get("/entity")
                .param("text", "Seattle")
                .param("type", "MADE_UP")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains("MADE_UP")
        assertThat(body).contains("ignored")
    }

    @Test
    @DisplayName("blank text param renders an empty-state prompt without an error")
    fun blankTextRendersEmptyState() {
        val body = mockMvc.perform(
            get("/entity")
                .param("text", "   ")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains("Provide an entity text to search")
    }

    @Test
    @DisplayName("no matching chunks renders a no-results message with FTS fallback link")
    fun noMatchesRendersFallback() {
        val body = mockMvc.perform(
            get("/entity")
                .param("text", "NonExistentEntityXYZ123")
                .param("type", "PERSON")
                .with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertThat(body).contains("No chunks tagged with entity")
        assertThat(body).contains("/search?q=NonExistentEntityXYZ123")
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
        namedEntities: String?,
    ): Long {
        val file = fSFileRepository.findById(fileId).orElseThrow()
        val chunk = ContentChunk().apply {
            this.chunkNumber = chunkNumber
            this.chunkType = "Sentence"
            this.text = text
            this.namedEntities = namedEntities
            this.nlpProcessedAt = OffsetDateTime.now()
            this.concept = file
        }
        return contentChunkRepository.save(chunk).id!!
    }
}
