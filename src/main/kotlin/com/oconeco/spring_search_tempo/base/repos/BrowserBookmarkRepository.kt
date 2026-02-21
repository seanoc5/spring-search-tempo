package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.BrowserBookmark
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query


interface BrowserBookmarkRepository : JpaRepository<BrowserBookmark, Long> {

    fun findByUrl(url: String): BrowserBookmark?

    fun findByUri(uri: String): BrowserBookmark?

    fun findByFirefoxPlaceId(firefoxPlaceId: Long): BrowserBookmark?

    fun findByDomain(domain: String, pageable: Pageable): Page<BrowserBookmark>

    fun countByBrowserProfileId(browserProfileId: Long): Long

    fun existsByUrl(url: String): Boolean

    @Query("SELECT DISTINCT b.domain FROM BrowserBookmark b WHERE b.domain IS NOT NULL ORDER BY b.domain")
    fun findDistinctDomains(): List<String>

    @Query("""
        SELECT b.domain, COUNT(b)
        FROM BrowserBookmark b
        WHERE b.domain IS NOT NULL
        GROUP BY b.domain
        ORDER BY COUNT(b) DESC
    """)
    fun findDomainCounts(pageable: Pageable): List<Array<Any>>

    /**
     * Find bookmarks that need content fetching (no bodyText yet).
     */
    @Query("""
        SELECT b FROM BrowserBookmark b
        WHERE b.fetchedAt IS NULL
        AND b.url IS NOT NULL
        AND (b.scheme = 'http' OR b.scheme = 'https')
    """)
    fun findBookmarksNeedingFetch(pageable: Pageable): Page<BrowserBookmark>

    /**
     * Find bookmarks that need chunking (have bodyText but not yet chunked or re-chunked).
     */
    @Query("""
        SELECT b FROM BrowserBookmark b
        WHERE b.bodyText IS NOT NULL
        AND (b.chunkedAt IS NULL OR b.lastUpdated > b.chunkedAt)
    """)
    fun findBookmarksNeedingChunking(pageable: Pageable): Page<BrowserBookmark>

    /**
     * Full-text search across bookmarks.
     */
    @Query(
        value = """
            SELECT * FROM browser_bookmark
            WHERE fts_vector @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank(fts_vector, plainto_tsquery('english', :query)) DESC
        """,
        countQuery = """
            SELECT COUNT(*) FROM browser_bookmark
            WHERE fts_vector @@ plainto_tsquery('english', :query)
        """,
        nativeQuery = true
    )
    fun searchByText(query: String, pageable: Pageable): Page<BrowserBookmark>

}
