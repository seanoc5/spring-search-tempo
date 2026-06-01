package com.oconeco.spring_search_tempo.base.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager


/**
 * Unit tests for [FirefoxPlacesService.readHistory] driven by a synthetic
 * places.sqlite database. Mirrors the real Firefox schema closely enough
 * to exercise the watermark, retention cutoff, and bookmark-exclusion
 * branches; no real Firefox install is required.
 */
class FirefoxPlacesServiceHistoryTest {

    @TempDir
    lateinit var tempDir: Path

    private val service = FirefoxPlacesService()

    @Test
    fun `readHistory returns all history entries when no filters apply`() {
        val db = tempDir.resolve("places.sqlite")
        buildPlacesDb(db) { conn ->
            insertPlace(conn, id = 1, url = "https://example.com/a", title = "A",
                visitCount = 5, lastVisitPrTime = nowPrTime(daysAgo = 1))
            insertPlace(conn, id = 2, url = "https://example.com/b", title = "B",
                visitCount = 1, lastVisitPrTime = nowPrTime(daysAgo = 2))
        }

        val history = service.readHistory(db, sinceVisitPrTime = null, retentionDays = null)

        assertThat(history).hasSize(2)
        assertThat(history.map { it.url })
            .containsExactlyInAnyOrder("https://example.com/a", "https://example.com/b")
    }

    @Test
    fun `readHistory excludes places with zero visit_count or null last_visit_date`() {
        val db = tempDir.resolve("places.sqlite")
        buildPlacesDb(db) { conn ->
            insertPlace(conn, id = 1, url = "https://visited.example", title = "v",
                visitCount = 3, lastVisitPrTime = nowPrTime(daysAgo = 1))
            insertPlace(conn, id = 2, url = "https://never-visited.example", title = "nv",
                visitCount = 0, lastVisitPrTime = nowPrTime(daysAgo = 1))
            insertPlace(conn, id = 3, url = "https://null-date.example", title = "nd",
                visitCount = 5, lastVisitPrTime = null)
        }

        val history = service.readHistory(db)

        assertThat(history.map { it.url }).containsExactly("https://visited.example")
    }

    @Test
    fun `readHistory excludes place URLs and place colon-prefixed URLs`() {
        val db = tempDir.resolve("places.sqlite")
        buildPlacesDb(db) { conn ->
            insertPlace(conn, id = 1, url = "place:type=6", title = "internal",
                visitCount = 5, lastVisitPrTime = nowPrTime(daysAgo = 1))
            insertPlace(conn, id = 2, url = "https://real.example", title = "real",
                visitCount = 5, lastVisitPrTime = nowPrTime(daysAgo = 1))
        }

        val history = service.readHistory(db)

        assertThat(history.map { it.url }).containsExactly("https://real.example")
    }

    @Test
    fun `readHistory applies sinceVisitPrTime watermark`() {
        val db = tempDir.resolve("places.sqlite")
        val oldTime = nowPrTime(daysAgo = 10)
        val newTime = nowPrTime(daysAgo = 1)
        buildPlacesDb(db) { conn ->
            insertPlace(conn, id = 1, url = "https://old.example", title = "old",
                visitCount = 2, lastVisitPrTime = oldTime)
            insertPlace(conn, id = 2, url = "https://new.example", title = "new",
                visitCount = 2, lastVisitPrTime = newTime)
        }

        val watermark = nowPrTime(daysAgo = 5)
        val history = service.readHistory(db, sinceVisitPrTime = watermark)

        assertThat(history.map { it.url }).containsExactly("https://new.example")
        assertThat(history.single().lastVisitDatePrTime).isEqualTo(newTime)
    }

    @Test
    fun `readHistory applies retention cutoff`() {
        val db = tempDir.resolve("places.sqlite")
        buildPlacesDb(db) { conn ->
            insertPlace(conn, id = 1, url = "https://ancient.example", title = "anc",
                visitCount = 9, lastVisitPrTime = nowPrTime(daysAgo = 400))
            insertPlace(conn, id = 2, url = "https://recent.example", title = "rec",
                visitCount = 9, lastVisitPrTime = nowPrTime(daysAgo = 5))
        }

        val history = service.readHistory(db, retentionDays = 30)

        assertThat(history.map { it.url }).containsExactly("https://recent.example")
    }

    @Test
    fun `readHistory excludes URLs that are bookmarked`() {
        val db = tempDir.resolve("places.sqlite")
        buildPlacesDb(db) { conn ->
            insertPlace(conn, id = 1, url = "https://bookmarked.example", title = "bm",
                visitCount = 4, lastVisitPrTime = nowPrTime(daysAgo = 1))
            insertPlace(conn, id = 2, url = "https://history-only.example", title = "ho",
                visitCount = 4, lastVisitPrTime = nowPrTime(daysAgo = 1))
            // Bookmark row referencing place 1, parent = MENU_ID (real bookmark, not a tag)
            insertBookmark(conn, id = 100, fk = 1, parent = FirefoxPlacesService.MENU_ID, type = 1)
        }

        val history = service.readHistory(db)

        assertThat(history.map { it.url }).containsExactly("https://history-only.example")
    }

    @Test
    fun `readHistory still includes URLs that are only tagged (tag association row)`() {
        // Firefox stores tags as bookmark rows nested under a tag folder that
        // is itself a child of TAGS_ROOT (id=4). Those rows must NOT count as
        // "bookmarked" for the purpose of the history exclusion filter.
        val db = tempDir.resolve("places.sqlite")
        buildPlacesDb(db) { conn ->
            insertPlace(conn, id = 1, url = "https://tagged-but-not-bookmarked.example",
                title = "t", visitCount = 4, lastVisitPrTime = nowPrTime(daysAgo = 1))
            // Tag folder under TAGS_ROOT
            insertBookmark(conn, id = 50, fk = null,
                parent = FirefoxPlacesService.TAGS_ROOT_ID, type = 2, title = "favs")
            // Tag association row: type=1, parent = the tag folder
            insertBookmark(conn, id = 51, fk = 1, parent = 50, type = 1)
        }

        val history = service.readHistory(db)

        assertThat(history.map { it.url })
            .containsExactly("https://tagged-but-not-bookmarked.example")
    }

    // --- helpers --------------------------------------------------------

    private fun nowPrTime(daysAgo: Long): Long {
        val nowMicros = System.currentTimeMillis() * 1000L
        val backMicros = daysAgo * 24L * 60L * 60L * 1000L * 1000L
        return nowMicros - backMicros
    }

    private fun buildPlacesDb(path: Path, populate: (Connection) -> Unit) {
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
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
                st.executeUpdate(
                    """
                    CREATE TABLE moz_bookmarks (
                        id INTEGER PRIMARY KEY,
                        fk INTEGER,
                        parent INTEGER,
                        position INTEGER DEFAULT 0,
                        type INTEGER,
                        title TEXT,
                        dateAdded INTEGER
                    )
                    """.trimIndent()
                )
            }
            populate(conn)
        }
    }

    private fun insertPlace(
        conn: Connection,
        id: Long,
        url: String,
        title: String?,
        visitCount: Int,
        lastVisitPrTime: Long?
    ) {
        conn.prepareStatement(
            "INSERT INTO moz_places(id,url,title,visit_count,last_visit_date,frecency) VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, id)
            ps.setString(2, url)
            ps.setString(3, title)
            ps.setInt(4, visitCount)
            if (lastVisitPrTime == null) ps.setNull(5, java.sql.Types.INTEGER)
            else ps.setLong(5, lastVisitPrTime)
            ps.setInt(6, 100)
            ps.executeUpdate()
        }
    }

    private fun insertBookmark(
        conn: Connection,
        id: Long,
        fk: Long?,
        parent: Long,
        type: Int,
        title: String? = null
    ) {
        conn.prepareStatement(
            "INSERT INTO moz_bookmarks(id,fk,parent,type,title) VALUES(?,?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, id)
            if (fk == null) ps.setNull(2, java.sql.Types.INTEGER) else ps.setLong(2, fk)
            ps.setLong(3, parent)
            ps.setInt(4, type)
            ps.setString(5, title)
            ps.executeUpdate()
        }
    }
}
