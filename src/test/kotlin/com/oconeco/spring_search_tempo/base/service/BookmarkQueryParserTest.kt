package com.oconeco.spring_search_tempo.base.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookmarkQueryParserTest {

    @Test
    fun `null or blank query yields empty parsed result`() {
        val parsedNull = BookmarkQueryParser.parse(null)
        val parsedBlank = BookmarkQueryParser.parse("   ")

        assertThat(parsedNull.residualQuery).isEmpty()
        assertThat(parsedNull.tagFilters).isEmpty()
        assertThat(parsedNull.folderFilter).isNull()
        assertThat(parsedBlank.residualQuery).isEmpty()
        assertThat(parsedBlank.hasFacetFilter).isFalse()
    }

    @Test
    fun `plain query passes through unchanged`() {
        val parsed = BookmarkQueryParser.parse("spring kotlin")
        assertThat(parsed.residualQuery).isEqualTo("spring kotlin")
        assertThat(parsed.tagFilters).isEmpty()
        assertThat(parsed.folderFilter).isNull()
        assertThat(parsed.hasFacetFilter).isFalse()
    }

    @Test
    fun `single tag token is extracted and stripped from residual`() {
        val parsed = BookmarkQueryParser.parse("kotlin tag:work")
        assertThat(parsed.residualQuery).isEqualTo("kotlin")
        assertThat(parsed.tagFilters).containsExactly("work")
        assertThat(parsed.folderFilter).isNull()
        assertThat(parsed.hasFacetFilter).isTrue()
    }

    @Test
    fun `multiple tag tokens accumulate as set`() {
        val parsed = BookmarkQueryParser.parse("tag:work tag:Dev tag:work")
        assertThat(parsed.residualQuery).isEmpty()
        assertThat(parsed.tagFilters).containsExactlyInAnyOrder("work", "dev")
    }

    @Test
    fun `tag values are lowercased for case-insensitive matching`() {
        val parsed = BookmarkQueryParser.parse("tag:Work tag:HOME")
        assertThat(parsed.tagFilters).containsExactlyInAnyOrder("work", "home")
    }

    @Test
    fun `quoted tag value preserves whitespace`() {
        val parsed = BookmarkQueryParser.parse("""tag:"side project" spring""")
        assertThat(parsed.residualQuery).isEqualTo("spring")
        assertThat(parsed.tagFilters).containsExactly("side project")
    }

    @Test
    fun `folder token is extracted with bare value`() {
        val parsed = BookmarkQueryParser.parse("react folder:Dev/Kotlin")
        assertThat(parsed.residualQuery).isEqualTo("react")
        assertThat(parsed.folderFilter).isEqualTo("Dev/Kotlin")
    }

    @Test
    fun `quoted folder value preserves spaces`() {
        val parsed = BookmarkQueryParser.parse("""folder:"Bookmarks Toolbar/Dev" kotlin""")
        assertThat(parsed.residualQuery).isEqualTo("kotlin")
        assertThat(parsed.folderFilter).isEqualTo("Bookmarks Toolbar/Dev")
    }

    @Test
    fun `folder token last-write-wins for multiple occurrences`() {
        val parsed = BookmarkQueryParser.parse("folder:a folder:b")
        assertThat(parsed.folderFilter).isEqualTo("b")
    }

    @Test
    fun `combined tag and folder with free text leaves clean residual`() {
        val parsed = BookmarkQueryParser.parse("""spring tag:work folder:"Dev/Kotlin" boot""")
        assertThat(parsed.residualQuery).isEqualTo("spring boot")
        assertThat(parsed.tagFilters).containsExactly("work")
        assertThat(parsed.folderFilter).isEqualTo("Dev/Kotlin")
    }

    @Test
    fun `tokens with empty values are ignored`() {
        // We don't want to crash on `tag:` with no value — just drop it.
        val parsed = BookmarkQueryParser.parse("""tag:"" spring""")
        assertThat(parsed.residualQuery).isEqualTo("spring")
        assertThat(parsed.tagFilters).isEmpty()
    }

    @Test
    fun `case-insensitive token keys`() {
        val parsed = BookmarkQueryParser.parse("TAG:work FOLDER:Dev")
        assertThat(parsed.tagFilters).containsExactly("work")
        assertThat(parsed.folderFilter).isEqualTo("Dev")
    }
}
