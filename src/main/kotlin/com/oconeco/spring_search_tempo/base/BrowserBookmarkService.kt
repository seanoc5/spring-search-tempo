package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.BrowserBookmarkDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface BrowserBookmarkService {

    fun count(): Long

    fun findAll(pageable: Pageable): Page<BrowserBookmarkDTO>

    fun get(id: Long): BrowserBookmarkDTO

    fun create(browserBookmarkDTO: BrowserBookmarkDTO): Long

    fun update(id: Long, browserBookmarkDTO: BrowserBookmarkDTO)

    fun delete(id: Long)

    fun urlExists(url: String): Boolean

    /**
     * Find a bookmark by its URL.
     */
    fun findByUrl(url: String): BrowserBookmarkDTO?

    /**
     * Find bookmarks by domain.
     */
    fun findByDomain(domain: String, pageable: Pageable): Page<BrowserBookmarkDTO>

    /**
     * Get all distinct domains with bookmark counts.
     */
    fun findDistinctDomains(): List<String>

    /**
     * Get domain counts for analytics.
     */
    fun findDomainCounts(pageable: Pageable): List<Pair<String, Long>>

    /**
     * Count bookmarks for a specific profile.
     */
    fun countByBrowserProfileId(browserProfileId: Long): Long

    /**
     * Find bookmarks that need content fetching.
     */
    fun findBookmarksNeedingFetch(pageable: Pageable): Page<BrowserBookmarkDTO>

    /**
     * Mark a bookmark as fetched.
     */
    fun markAsFetched(bookmarkId: Long)

    /**
     * Mark a bookmark as chunked.
     */
    fun markAsChunked(bookmarkId: Long)

    /**
     * Full-text search across bookmarks.
     */
    fun searchByText(query: String, pageable: Pageable): Page<BrowserBookmarkDTO>

    fun getBrowserBookmarkValues(): Map<Long, Long>

}
