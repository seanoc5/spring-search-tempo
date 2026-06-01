package com.oconeco.spring_search_tempo.base.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Pure unit test for [FirefoxPlacesService] that builds a fixture SQLite
 * database matching Firefox's `places.sqlite` schema, populates it with
 * known bookmarks, tags, and folder hierarchy, and asserts the service
 * extracts them correctly.
 *
 * Schema kept minimal — only the columns the service actually reads.
 */
class FirefoxPlacesServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var placesDb: Path
    private val service = FirefoxPlacesService()

    @BeforeEach
    fun setUp() {
        placesDb = tempDir.resolve("places.sqlite")
        buildFixture(placesDb)
    }

    @AfterEach
    fun tearDown() {
        Files.deleteIfExists(placesDb)
    }

    @Test
    fun `reads bookmarks with tags from places sqlite`() {
        val bookmarks = service.readBookmarks(placesDb)

        // 3 real bookmarks (the tag-association entries should be excluded).
        assertThat(bookmarks).hasSize(3)
        assertThat(bookmarks.map { it.url })
            .containsExactlyInAnyOrder(
                "https://kotlinlang.org/",
                "https://spring.io/",
                "https://news.ycombinator.com/"
            )

        val kotlin = bookmarks.single { it.url == "https://kotlinlang.org/" }
        assertThat(kotlin.title).isEqualTo("Kotlin Programming Language")
        assertThat(kotlin.tags.map { it.name }).containsExactlyInAnyOrder("work", "dev")
        assertThat(kotlin.tags.map { it.displayName }).containsExactlyInAnyOrder("Work", "Dev")
        assertThat(kotlin.folderPath).isEqualTo("Bookmarks Toolbar/Dev")

        val spring = bookmarks.single { it.url == "https://spring.io/" }
        assertThat(spring.tags.map { it.name }).containsExactly("work")
        assertThat(spring.folderPath).isEqualTo("Bookmarks Toolbar/Dev")

        val news = bookmarks.single { it.url == "https://news.ycombinator.com/" }
        assertThat(news.tags).isEmpty()
        assertThat(news.folderPath).isEqualTo("Bookmarks Menu")
    }

    @Test
    fun `tag names are lowercased for matching but display name preserves case`() {
        val bookmarks = service.readBookmarks(placesDb)
        val allTags = bookmarks.flatMap { it.tags }

        assertThat(allTags.map { it.name }).allMatch { it == it.lowercase() }
        // "Work" tag folder retains its original case in displayName.
        assertThat(allTags.any { it.name == "work" && it.displayName == "Work" }).isTrue()
    }

    @Test
    fun `missing places sqlite returns empty list, no exception`() {
        val missing = tempDir.resolve("does-not-exist.sqlite")
        val result = service.readBookmarks(missing)
        assertThat(result).isEmpty()
    }

    // ---------------------------------------------------------------------
    // Fixture builder
    // ---------------------------------------------------------------------

    private fun buildFixture(dbPath: Path) {
        val url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        DriverManager.getConnection(url).use { conn ->
            createSchema(conn)
            seedData(conn)
        }
    }

    private fun createSchema(conn: Connection) {
        conn.createStatement().use { stmt ->
            // Minimal subset of moz_places — only columns FirefoxPlacesService reads.
            stmt.execute(
                """
                CREATE TABLE moz_places (
                    id INTEGER PRIMARY KEY,
                    url TEXT,
                    title TEXT,
                    visit_count INTEGER DEFAULT 0,
                    last_visit_date INTEGER,
                    frecency INTEGER DEFAULT 0
                )
                """.trimIndent()
            )
            // type=1: bookmark entry, type=2: folder. parent points at containing folder.
            stmt.execute(
                """
                CREATE TABLE moz_bookmarks (
                    id INTEGER PRIMARY KEY,
                    type INTEGER,
                    fk INTEGER,
                    parent INTEGER,
                    position INTEGER DEFAULT 0,
                    title TEXT,
                    dateAdded INTEGER
                )
                """.trimIndent()
            )
        }
    }

    private fun seedData(conn: Connection) {
        // Roots Firefox creates implicitly. The service treats these IDs as
        // well-known constants (see FirefoxPlacesService companion object).
        conn.prepareStatement(
            "INSERT INTO moz_bookmarks (id, type, parent, title) VALUES (?, 2, 0, ?)"
        ).use { stmt ->
            listOf(
                1L to "places-root",
                2L to "Bookmarks Menu",
                3L to "Bookmarks Toolbar",
                4L to "Tags",
                5L to "Other Bookmarks",
                6L to "Mobile Bookmarks"
            ).forEach { (id, title) ->
                stmt.setLong(1, id); stmt.setString(2, title); stmt.executeUpdate()
            }
        }

        // Folder under Bookmarks Toolbar: "Dev" (id=10).
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                "INSERT INTO moz_bookmarks (id, type, parent, title, position) " +
                    "VALUES (10, 2, 3, 'Dev', 0)"
            )
        }

        // Tag folders (children of TAGS_ROOT_ID=4): "Work" (id=20), "Dev" (id=21).
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                "INSERT INTO moz_bookmarks (id, type, parent, title) " +
                    "VALUES (20, 2, 4, 'Work'), (21, 2, 4, 'Dev')"
            )
        }

        // moz_places entries.
        conn.prepareStatement(
            "INSERT INTO moz_places (id, url, title, frecency) VALUES (?, ?, ?, ?)"
        ).use { stmt ->
            listOf(
                Triple(100L, "https://kotlinlang.org/", "Kotlin Programming Language"),
                Triple(101L, "https://spring.io/", "Spring"),
                Triple(102L, "https://news.ycombinator.com/", "Hacker News")
            ).forEach { (id, url, title) ->
                stmt.setLong(1, id); stmt.setString(2, url); stmt.setString(3, title)
                stmt.setInt(4, 100); stmt.executeUpdate()
            }
        }

        // Real bookmark entries (type=1, fk -> moz_places.id).
        conn.prepareStatement(
            "INSERT INTO moz_bookmarks (id, type, fk, parent, title) VALUES (?, 1, ?, ?, ?)"
        ).use { stmt ->
            // Kotlin homepage in Bookmarks Toolbar/Dev folder.
            stmt.setLong(1, 200); stmt.setLong(2, 100); stmt.setLong(3, 10)
            stmt.setString(4, "Kotlin Programming Language"); stmt.executeUpdate()
            // Spring homepage in Bookmarks Toolbar/Dev folder.
            stmt.setLong(1, 201); stmt.setLong(2, 101); stmt.setLong(3, 10)
            stmt.setString(4, "Spring"); stmt.executeUpdate()
            // HN under Bookmarks Menu directly.
            stmt.setLong(1, 202); stmt.setLong(2, 102); stmt.setLong(3, 2)
            stmt.setString(4, "Hacker News"); stmt.executeUpdate()
        }

        // Tag associations: bookmark-under-tag-folder, with fk pointing at the place.
        conn.prepareStatement(
            "INSERT INTO moz_bookmarks (id, type, fk, parent, title) VALUES (?, 1, ?, ?, NULL)"
        ).use { stmt ->
            // Kotlin tagged Work (parent=20) and Dev (parent=21).
            stmt.setLong(1, 300); stmt.setLong(2, 100); stmt.setLong(3, 20); stmt.executeUpdate()
            stmt.setLong(1, 301); stmt.setLong(2, 100); stmt.setLong(3, 21); stmt.executeUpdate()
            // Spring tagged Work only.
            stmt.setLong(1, 302); stmt.setLong(2, 101); stmt.setLong(3, 20); stmt.executeUpdate()
        }
    }
}
