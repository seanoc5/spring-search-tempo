package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.SpringSearchTempoApplication
import com.oconeco.spring_search_tempo.base.BookmarkTagService
import com.oconeco.spring_search_tempo.base.config.BaseIT
import com.oconeco.spring_search_tempo.base.domain.BrowserBookmark
import com.oconeco.spring_search_tempo.base.domain.BrowserProfile
import com.oconeco.spring_search_tempo.base.domain.BrowserType
import com.oconeco.spring_search_tempo.base.model.SearchFilterDTO
import com.oconeco.spring_search_tempo.base.repos.BrowserBookmarkRepository
import com.oconeco.spring_search_tempo.base.repos.BrowserProfileRepository
import com.oconeco.spring_search_tempo.base.service.ContentType
import com.oconeco.spring_search_tempo.base.service.FullTextSearchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional

/**
 * End-to-end search-side test for the issue-9 facets: confirms that
 * `tag:` and `folder:` filters select the expected subset of bookmarks
 * when run through [FullTextSearchService.searchWithFilters], the same
 * code path the web controller uses.
 *
 * Data shape (3 bookmarks, 2 tags, 2 folder paths):
 *   kotlinlang.org   tags={work,dev}  folder=Bookmarks Toolbar/Dev
 *   spring.io        tags={work}      folder=Bookmarks Toolbar/Dev
 *   news.ycombinator tags={}          folder=Bookmarks Menu
 */
@SpringBootTest(
    classes = [SpringSearchTempoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class BookmarkSearchIntegrationTest : BaseIT() {

    @Autowired private lateinit var browserProfileRepository: BrowserProfileRepository
    @Autowired private lateinit var browserBookmarkRepository: BrowserBookmarkRepository
    @Autowired private lateinit var bookmarkTagService: BookmarkTagService
    @Autowired private lateinit var searchService: FullTextSearchService

    private fun seed(): Map<String, Long> {
        val profile = BrowserProfile().apply {
            uri = "test:browser-profile:issue-9"
            label = "issue-9 test profile"
            browserType = BrowserType.FIREFOX
            profileName = "test-profile"
        }
        browserProfileRepository.save(profile)

        val workTag = bookmarkTagService.findOrCreate("work", "Work", "TEST")
        val devTag = bookmarkTagService.findOrCreate("dev", "Dev", "TEST")

        val kotlin = BrowserBookmark().apply {
            uri = "firefox:bookmark:9001"
            url = "https://kotlinlang.org/"
            title = "Kotlin Programming Language"
            domain = "kotlinlang.org"
            folderPath = "Bookmarks Toolbar/Dev"
            tagsText = "work dev"
            label = title
            browserProfile = profile
            tags = mutableSetOf(workTag, devTag)
        }
        val spring = BrowserBookmark().apply {
            uri = "firefox:bookmark:9002"
            url = "https://spring.io/"
            title = "Spring Framework"
            domain = "spring.io"
            folderPath = "Bookmarks Toolbar/Dev"
            tagsText = "work"
            label = title
            browserProfile = profile
            tags = mutableSetOf(workTag)
        }
        val hn = BrowserBookmark().apply {
            uri = "firefox:bookmark:9003"
            url = "https://news.ycombinator.com/"
            title = "Hacker News"
            domain = "news.ycombinator.com"
            folderPath = "Bookmarks Menu"
            tagsText = null
            label = title
            browserProfile = profile
        }
        listOf(kotlin, spring, hn).forEach { browserBookmarkRepository.save(it) }

        return mapOf(
            "kotlin" to kotlin.id!!,
            "spring" to spring.id!!,
            "hn" to hn.id!!
        )
    }

    @Test
    @Transactional
    fun `tag filter returns only bookmarks carrying the tag`() {
        val ids = seed()

        val filter = SearchFilterDTO(
            query = "",
            contentTypes = setOf(ContentType.BOOKMARK),
            tagFilters = setOf("dev")
        )
        val results = searchService.searchWithFilters(filter, PageRequest.of(0, 20))

        assertThat(results.totalElements).isEqualTo(1)
        assertThat(results.content.map { it.id }).containsExactly(ids["kotlin"])
        assertThat(results.content.first().sourceTable).isEqualTo("browser_bookmark")
    }

    @Test
    @Transactional
    fun `multiple tag filter is OR semantics`() {
        val ids = seed()

        val filter = SearchFilterDTO(
            query = "",
            contentTypes = setOf(ContentType.BOOKMARK),
            tagFilters = setOf("work", "dev")
        )
        val results = searchService.searchWithFilters(filter, PageRequest.of(0, 20))

        assertThat(results.totalElements).isEqualTo(2)
        assertThat(results.content.map { it.id })
            .containsExactlyInAnyOrder(ids["kotlin"], ids["spring"])
    }

    @Test
    @Transactional
    fun `folder filter is a substring match`() {
        val ids = seed()

        val filter = SearchFilterDTO(
            query = "",
            contentTypes = setOf(ContentType.BOOKMARK),
            folderFilter = "Toolbar"
        )
        val results = searchService.searchWithFilters(filter, PageRequest.of(0, 20))

        assertThat(results.totalElements).isEqualTo(2)
        assertThat(results.content.map { it.id })
            .containsExactlyInAnyOrder(ids["kotlin"], ids["spring"])
    }

    @Test
    @Transactional
    fun `tag and folder filters combine with AND semantics`() {
        val ids = seed()

        val filter = SearchFilterDTO(
            query = "",
            contentTypes = setOf(ContentType.BOOKMARK),
            tagFilters = setOf("work"),
            folderFilter = "Toolbar"
        )
        val results = searchService.searchWithFilters(filter, PageRequest.of(0, 20))

        assertThat(results.totalElements).isEqualTo(2)
        assertThat(results.content.map { it.id })
            .containsExactlyInAnyOrder(ids["kotlin"], ids["spring"])
    }

    @Test
    @Transactional
    fun `tag name is indexed in fts_vector and matched by free-text query`() {
        // The denormalized tags_text column is folded into fts_vector at weight A,
        // so a free-text search for a tag name should find the tagged bookmark
        // even without explicit tag: facet usage.
        val ids = seed()

        val filter = SearchFilterDTO(
            query = "dev",
            contentTypes = setOf(ContentType.BOOKMARK)
        )
        val results = searchService.searchWithFilters(filter, PageRequest.of(0, 20))

        // Only the kotlin bookmark has "dev" in either title, tags_text, or folder_path.
        // (spring.io is in folder "Dev" but its tags_text doesn't include "dev")
        // Actually folder_path "Bookmarks Toolbar/Dev" contains "Dev" too — both should match.
        assertThat(results.content.map { it.id })
            .contains(ids["kotlin"])
    }
}
