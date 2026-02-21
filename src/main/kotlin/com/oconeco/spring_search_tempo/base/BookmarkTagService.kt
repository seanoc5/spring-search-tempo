package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.domain.BookmarkTag
import com.oconeco.spring_search_tempo.base.model.BookmarkTagDTO


interface BookmarkTagService {

    fun count(): Long

    fun findAll(): List<BookmarkTagDTO>

    fun get(id: Long): BookmarkTagDTO

    fun create(bookmarkTagDTO: BookmarkTagDTO): Long

    fun update(id: Long, bookmarkTagDTO: BookmarkTagDTO)

    fun delete(id: Long)

    fun tagNameExists(name: String): Boolean

    /**
     * Find or create a tag by its normalized name.
     *
     * @param name Normalized (lowercase) tag name
     * @param displayName Original display name with case preserved
     * @param source Source of the tag (FIREFOX, CHROME, USER)
     * @return The existing or newly created tag entity
     */
    fun findOrCreate(name: String, displayName: String, source: String): BookmarkTag

    /**
     * Find or create multiple tags at once (batch operation).
     *
     * @param tagPairs List of pairs: (normalized name, display name)
     * @param source Source of the tags
     * @return Set of tag entities
     */
    fun findOrCreateAll(tagPairs: List<Pair<String, String>>, source: String): Set<BookmarkTag>

    /**
     * Find the most popular tags by usage count.
     */
    fun findPopular(limit: Int): List<BookmarkTagDTO>

    /**
     * Update usage counts for all tags (recalculate from bookmark relationships).
     */
    fun updateUsageCounts()

    /**
     * Increment usage count for specified tags.
     */
    fun incrementUsageCounts(tagIds: Collection<Long>)

}
