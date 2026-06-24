package com.oconeco.spring_search_tempo.base.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the XSS guardrail described in issue #152.
 *
 * The contract is narrow: pass through `<mark>` / `</mark>` markers unchanged
 * so the search-results templates can render the highlight, and escape
 * everything else. These tests pin that contract so future tweaks to the
 * sanitizer don't quietly widen the whitelist.
 */
@DisplayName("SnippetSanitizer")
class SnippetSanitizerTest {

    @Test
    @DisplayName("preserves <mark> markers while escaping foreign tags")
    fun preservesMarkAndEscapesOthers() {
        val raw = "lorem <mark>fox</mark> ipsum <script>alert('xss')</script> end"
        val sanitized = SnippetSanitizer.sanitize(raw)

        assertThat(sanitized).contains("<mark>fox</mark>")
        assertThat(sanitized).doesNotContain("<script>")
        assertThat(sanitized).contains("&lt;script&gt;")
        assertThat(sanitized).contains("&#39;xss&#39;")
    }

    @Test
    @DisplayName("escapes lone < and & in raw text")
    fun escapesEntities() {
        val sanitized = SnippetSanitizer.sanitize("a < b && c > d")
        assertThat(sanitized).isEqualTo("a &lt; b &amp;&amp; c &gt; d")
    }

    @Test
    @DisplayName("normalizes uppercase MARK markers to lowercase")
    fun normalizesCase() {
        val sanitized = SnippetSanitizer.sanitize("<MARK>x</MARK>")
        assertThat(sanitized).isEqualTo("<mark>x</mark>")
    }

    @Test
    @DisplayName("returns null for null input")
    fun nullPassthrough() {
        assertThat(SnippetSanitizer.sanitize(null)).isNull()
    }

    @Test
    @DisplayName("highlight wraps matched terms case-insensitively")
    fun highlightMatchesTerms() {
        val highlighted = SnippetSanitizer.highlight(
            "Kotlin is great for Spring Boot apps",
            listOf("kotlin"),
            maxWords = 20
        )
        assertThat(highlighted).contains("<mark>Kotlin</mark>")
    }

    @Test
    @DisplayName("highlight on empty/blank input returns the input as-is")
    fun highlightBlank() {
        assertThat(SnippetSanitizer.highlight("", listOf("x"))).isEmpty()
        assertThat(SnippetSanitizer.highlight(null, listOf("x"))).isNull()
    }

    @Test
    @DisplayName("highlight + sanitize composes safely with user-controlled text")
    fun highlightThenSanitize() {
        val raw = "Friendly <script> from user about kotlin"
        val sanitized = SnippetSanitizer.sanitize(
            SnippetSanitizer.highlight(raw, listOf("kotlin"))
        )
        assertThat(sanitized).contains("<mark>kotlin</mark>")
        assertThat(sanitized).doesNotContain("<script>")
        assertThat(sanitized).contains("&lt;script&gt;")
    }
}
