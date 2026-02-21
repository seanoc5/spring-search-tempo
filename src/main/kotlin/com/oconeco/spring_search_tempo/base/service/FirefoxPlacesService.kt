package com.oconeco.spring_search_tempo.base.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists


/**
 * Service for reading Firefox places.sqlite database.
 *
 * Firefox stores bookmarks and history in a SQLite database called places.sqlite.
 * This service copies the database (Firefox locks it while running) and extracts
 * bookmark data including tags.
 *
 * Firefox bookmark structure:
 * - moz_places: URL storage (id, url, title, visit_count, frecency, last_visit_date)
 * - moz_bookmarks: Bookmark entries pointing to places (id, fk -> moz_places.id, parent, title, dateAdded)
 * - Tags are special folders in moz_bookmarks with parent = 4 (Tags root folder)
 * - Bookmarks tagged with X have entries in moz_bookmarks with parent = X's folder id
 */
@Service
class FirefoxPlacesService {

    companion object {
        private val log = LoggerFactory.getLogger(FirefoxPlacesService::class.java)

        // Firefox root folder IDs (fixed)
        const val ROOT_ID = 1L
        const val MENU_ID = 2L
        const val TOOLBAR_ID = 3L
        const val TAGS_ROOT_ID = 4L
        const val UNFILED_ID = 5L
        const val MOBILE_ID = 6L

        // Firefox PRTime is microseconds since Unix epoch
        fun prTimeToOffsetDateTime(prTime: Long?): OffsetDateTime? {
            if (prTime == null || prTime == 0L) return null
            val instant = Instant.ofEpochMilli(prTime / 1000)
            return OffsetDateTime.ofInstant(instant, ZoneId.systemDefault())
        }

        fun folderIdToName(folderId: Long): String = when (folderId) {
            MENU_ID -> "Bookmarks Menu"
            TOOLBAR_ID -> "Bookmarks Toolbar"
            UNFILED_ID -> "Other Bookmarks"
            MOBILE_ID -> "Mobile Bookmarks"
            else -> ""
        }
    }

    /**
     * Data class for bookmark data read from Firefox.
     */
    data class FirefoxBookmarkData(
        val placeId: Long,
        val bookmarkId: Long,
        val url: String,
        val title: String?,
        val visitCount: Int,
        val lastVisitDate: OffsetDateTime?,
        val frecency: Int,
        val dateAdded: OffsetDateTime?,
        val folderPath: String,
        val tags: List<TagData>
    )

    data class TagData(
        val name: String,
        val displayName: String
    )

    /**
     * Read all bookmarks from a Firefox places.sqlite database.
     *
     * @param placesDbPath Path to the places.sqlite file
     * @return List of bookmark data with tags
     */
    fun readBookmarks(placesDbPath: Path): List<FirefoxBookmarkData> {
        if (!placesDbPath.exists()) {
            log.error("places.sqlite does not exist: {}", placesDbPath)
            return emptyList()
        }

        // Copy database to temp file (Firefox locks it while running)
        val tempDb = Files.createTempFile("places_copy_", ".sqlite")
        try {
            log.info("Copying places.sqlite to temp file for reading")
            Files.copy(placesDbPath, tempDb, StandardCopyOption.REPLACE_EXISTING)

            return readFromDatabase(tempDb)
        } finally {
            tempDb.deleteIfExists()
        }
    }

    private fun readFromDatabase(dbPath: Path): List<FirefoxBookmarkData> {
        val jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"

        DriverManager.getConnection(jdbcUrl).use { conn ->
            // Build folder path map
            val folderPaths = buildFolderPaths(conn)

            // Get tag folders (children of TAGS_ROOT)
            val tagFolders = getTagFolders(conn)

            // Get bookmark -> tag relationships
            val bookmarkTags = getBookmarkTags(conn, tagFolders)

            // Read bookmarks
            return readBookmarksFromDb(conn, folderPaths, bookmarkTags)
        }
    }

    /**
     * Build a map of folder ID -> folder path.
     */
    private fun buildFolderPaths(conn: Connection): Map<Long, String> {
        val folderPaths = mutableMapOf<Long, String>()

        // Initialize root folders
        folderPaths[ROOT_ID] = ""
        folderPaths[MENU_ID] = "Bookmarks Menu"
        folderPaths[TOOLBAR_ID] = "Bookmarks Toolbar"
        folderPaths[TAGS_ROOT_ID] = "Tags"
        folderPaths[UNFILED_ID] = "Other Bookmarks"
        folderPaths[MOBILE_ID] = "Mobile Bookmarks"

        // Query all folders (type = 2 is folder)
        val sql = """
            SELECT id, parent, title FROM moz_bookmarks
            WHERE type = 2 AND id NOT IN (1, 2, 3, 4, 5, 6)
            ORDER BY parent, position
        """

        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val id = rs.getLong("id")
                    val parent = rs.getLong("parent")
                    val title = rs.getString("title") ?: "Unnamed"

                    val parentPath = folderPaths[parent] ?: ""
                    folderPaths[id] = if (parentPath.isEmpty()) title else "$parentPath/$title"
                }
            }
        }

        return folderPaths
    }

    /**
     * Get tag folders (direct children of Tags root folder).
     * Returns map of folder ID -> tag name.
     */
    private fun getTagFolders(conn: Connection): Map<Long, String> {
        val tagFolders = mutableMapOf<Long, String>()

        val sql = """
            SELECT id, title FROM moz_bookmarks
            WHERE parent = ? AND type = 2
        """

        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, TAGS_ROOT_ID)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val id = rs.getLong("id")
                    val title = rs.getString("title") ?: continue
                    tagFolders[id] = title
                }
            }
        }

        log.debug("Found {} tag folders", tagFolders.size)
        return tagFolders
    }

    /**
     * Get bookmark -> tag relationships.
     * Firefox stores tags as bookmark entries inside tag folders.
     */
    private fun getBookmarkTags(conn: Connection, tagFolders: Map<Long, String>): Map<Long, List<TagData>> {
        if (tagFolders.isEmpty()) return emptyMap()

        val bookmarkTags = mutableMapOf<Long, MutableList<TagData>>()

        // Find all bookmark entries that are children of tag folders
        val tagFolderIds = tagFolders.keys.joinToString(",")
        val sql = """
            SELECT b.fk AS place_id, parent, tf.title AS tag_name
            FROM moz_bookmarks b
            JOIN moz_bookmarks tf ON tf.id = b.parent
            WHERE b.parent IN ($tagFolderIds) AND b.type = 1 AND b.fk IS NOT NULL
        """

        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val placeId = rs.getLong("place_id")
                    val tagName = rs.getString("tag_name") ?: continue

                    val tagData = TagData(
                        name = tagName.lowercase(),
                        displayName = tagName
                    )

                    bookmarkTags.getOrPut(placeId) { mutableListOf() }.add(tagData)
                }
            }
        }

        log.debug("Found tags for {} bookmarks", bookmarkTags.size)
        return bookmarkTags
    }

    /**
     * Read bookmarks from the database.
     */
    private fun readBookmarksFromDb(
        conn: Connection,
        folderPaths: Map<Long, String>,
        bookmarkTags: Map<Long, List<TagData>>
    ): List<FirefoxBookmarkData> {
        val bookmarks = mutableListOf<FirefoxBookmarkData>()

        // Query bookmarks (type = 1 is bookmark)
        // Exclude bookmarks in Tags root (those are tag associations, not real bookmarks)
        val sql = """
            SELECT
                p.id AS place_id,
                b.id AS bookmark_id,
                p.url,
                COALESCE(b.title, p.title) AS title,
                p.visit_count,
                p.last_visit_date,
                p.frecency,
                b.dateAdded,
                b.parent
            FROM moz_bookmarks b
            JOIN moz_places p ON b.fk = p.id
            WHERE b.type = 1
            AND p.url IS NOT NULL
            AND p.url NOT LIKE 'place:%'
            AND NOT EXISTS (
                SELECT 1 FROM moz_bookmarks parent
                WHERE parent.id = b.parent AND parent.parent = ?
            )
            ORDER BY b.dateAdded DESC
        """

        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, TAGS_ROOT_ID)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val placeId = rs.getLong("place_id")
                    val bookmarkId = rs.getLong("bookmark_id")
                    val url = rs.getString("url") ?: continue
                    val title = rs.getString("title")
                    val visitCount = rs.getInt("visit_count")
                    val lastVisitDate = rs.getLong("last_visit_date")
                    val frecency = rs.getInt("frecency")
                    val dateAdded = rs.getLong("dateAdded")
                    val parent = rs.getLong("parent")

                    val folderPath = folderPaths[parent] ?: ""
                    val tags = bookmarkTags[placeId] ?: emptyList()

                    bookmarks.add(
                        FirefoxBookmarkData(
                            placeId = placeId,
                            bookmarkId = bookmarkId,
                            url = url,
                            title = title,
                            visitCount = visitCount,
                            lastVisitDate = prTimeToOffsetDateTime(lastVisitDate),
                            frecency = frecency,
                            dateAdded = prTimeToOffsetDateTime(dateAdded),
                            folderPath = folderPath,
                            tags = tags
                        )
                    )
                }
            }
        }

        log.info("Read {} bookmarks from places.sqlite", bookmarks.size)
        return bookmarks
    }

}
