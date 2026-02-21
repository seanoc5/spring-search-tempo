package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime


/**
 * Browser bookmark entity.
 *
 * Represents an imported bookmark from a browser (Firefox, etc.) with
 * metadata, tags, and optional fetched page content for full-text search.
 */
@Entity
class BrowserBookmark : SaveableObject() {

    // Firefox-specific IDs for deduplication
    @Column
    var firefoxPlaceId: Long? = null

    @Column
    var firefoxBookmarkId: Long? = null

    /**
     * The bookmarked URL.
     */
    @Column(nullable = false, columnDefinition = "text")
    var url: String? = null

    /**
     * Bookmark title (from browser).
     */
    @Column(columnDefinition = "text")
    var title: String? = null

    /**
     * Extracted domain (e.g., "github.com") for analytics.
     */
    @Column(columnDefinition = "text")
    var domain: String? = null

    /**
     * URL scheme (http, https, file, etc.).
     */
    @Column(columnDefinition = "text")
    var scheme: String? = null

    /**
     * Visit count from browser history.
     */
    @Column
    var visitCount: Int? = null

    /**
     * Last visit timestamp from browser.
     */
    @Column
    var lastVisitDate: OffsetDateTime? = null

    /**
     * Firefox frecency score (frequency + recency).
     */
    @Column
    var frecency: Int? = null

    /**
     * When the bookmark was added in the browser.
     */
    @Column
    var dateAdded: OffsetDateTime? = null

    /**
     * Folder path in browser bookmark hierarchy (e.g., "Bookmarks Toolbar/Dev").
     */
    @Column(columnDefinition = "text")
    var folderPath: String? = null

    /**
     * Fetched page content (populated by future BookmarkFetchJob).
     */
    @Column(columnDefinition = "text")
    var bodyText: String? = null

    /**
     * When page content was last fetched.
     */
    @Column
    var fetchedAt: OffsetDateTime? = null

    /**
     * When content was last chunked into ContentChunks.
     */
    @Column
    var chunkedAt: OffsetDateTime? = null

    /**
     * PostgreSQL tsvector for full-text search.
     *
     * Includes weighted fields:
     * - Weight A: title
     * - Weight B: domain, folder_path
     * - Weight C: body_text (first 250K chars)
     * - Weight D: url
     */
    @Column(
        name = "fts_vector",
        columnDefinition = """
        tsvector GENERATED ALWAYS AS (
            setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
            setweight(to_tsvector('english', coalesce(domain, '')), 'B') ||
            setweight(to_tsvector('english', coalesce(folder_path, '')), 'B') ||
            setweight(to_tsvector('english', coalesce(substring(body_text, 1, 250000), '')), 'C') ||
            setweight(to_tsvector('english', coalesce(url, '')), 'D')
        ) STORED
        """,
        insertable = false,
        updatable = false
    )
    @JdbcTypeCode(SqlTypes.OTHER)
    var ftsVector: ByteArray? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "browser_profile_id")
    var browserProfile: BrowserProfile? = null

    @ManyToMany
    @JoinTable(
        name = "browser_bookmark_tags",
        joinColumns = [JoinColumn(name = "bookmark_id")],
        inverseJoinColumns = [JoinColumn(name = "tag_id")]
    )
    var tags: MutableSet<BookmarkTag> = mutableSetOf()

    @OneToMany(mappedBy = "browserBookmark")
    var contentChunks: MutableSet<ContentChunk> = mutableSetOf()

}
