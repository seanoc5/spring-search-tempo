package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.config.BaseIT
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

/**
 * Integration coverage for issue #152: search-result snippets must wrap
 * matched terms in `<mark>` and must HTML-escape any other markup found in
 * the source chunk text (XSS guardrail).
 *
 * Goes through the live MVC stack so the assertions cover the full chain:
 * SQL `ts_headline` → [com.oconeco.spring_search_tempo.base.util.SnippetSanitizer] →
 * Thymeleaf `th:utext` rendering on `templates/search/chunk-results.html`.
 */
@SpringBootTest(classes = [SpringSearchTempoApplication::class])
@AutoConfigureMockMvc
@DisplayName("Search result snippets render <mark> highlighting and escape unsafe HTML (issue #152)")
class SearchHighlightingIT : BaseIT() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var fsFileRepository: FSFileRepository
    @Autowired lateinit var contentChunkRepository: ContentChunkRepository

    @Test
    @DisplayName("GET /search/chunks?q=fox renders <mark>fox</mark> in the snippet")
    fun rendersMarkForMatchedTerm() {
        seedChunk(
            uri = "/it/issue-152/highlight.txt",
            text = "The quick brown fox jumps over the lazy dog. A fox is a clever creature."
        )

        val html = mockMvc.perform(
            get("/search/chunks").param("q", "fox").with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        assertThat(html)
            .describedAs("rendered HTML must contain the highlighted match")
            .contains("<mark>fox</mark>")
    }

    @Test
    @DisplayName("Source HTML metacharacters survive ts_headline and must be HTML-escaped by the sanitizer")
    fun escapesHtmlMetacharsKeepsMark() {
        // ts_headline strips standalone <script>...</script> tags as
        // punctuation, but lone metacharacters that sit *inside* word
        // tokens (e.g. R&D, fish&chips) survive into the snippet and are
        // exactly what the sanitizer has to escape before reaching the
        // browser. Use that path here so the assertion exercises the
        // guardrail and isn't masked by ts_headline's own stripping.
        seedChunk(
            uri = "/it/issue-152/xss.txt",
            text = "Salamander notes about R&D fish&chips and other tasty topics that ts_headline should keep intact for a clean preview snippet."
        )

        val html = mockMvc.perform(
            get("/search/chunks").param("q", "salamander").with(user(BaseIT.LOGIN).roles("USER"))
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        // Pull out just the chunk-result paragraph so other parts of the
        // page (search-box value, breadcrumbs) don't pollute the assertion.
        val snippetParagraph = html.lines()
            .firstOrNull { it.contains("<mark>") }
            ?: error("expected at least one <mark>-bearing line in the rendered page; got:\n$html")

        assertThat(snippetParagraph)
            .describedAs("the snippet must preserve the highlight tag")
            .contains("<mark>")
        assertThat(snippetParagraph)
            .describedAs("ampersand metacharacters from chunk text must be HTML-escaped")
            .contains("R&amp;D")
            .doesNotContain("R&D")
    }

    private fun seedChunk(uri: String, text: String): ContentChunk {
        val file = FSFile().apply {
            this.uri = uri
            this.label = uri.substringAfterLast('/')
            this.status = Status.CURRENT
            this.version = 0L
        }
        fsFileRepository.save(file)

        val chunk = ContentChunk().apply {
            this.concept = file
            this.text = text
            this.chunkNumber = 0
            this.chunkType = "Paragraph"
        }
        val saved = contentChunkRepository.save(chunk)
        // Hibernate doesn't auto-refresh the GENERATED fts_vector column on
        // insert; force a flush so the search query sees the populated vector.
        contentChunkRepository.flush()
        return saved
    }
}
