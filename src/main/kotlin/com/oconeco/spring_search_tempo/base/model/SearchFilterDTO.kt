package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.EmailCategory
import com.oconeco.spring_search_tempo.base.service.ContentType

/**
 * Filter parameters for full-text search.
 */
data class SearchFilterDTO(
    /**
     * The search query string.
     */
    val query: String,

    /**
     * Content types to include in results.
     * Defaults to all types.
     */
    val contentTypes: Set<ContentType> = ContentType.entries.toSet(),

    /**
     * Optional sentiment filter for chunks.
     * Values: "POSITIVE", "NEGATIVE", "NEUTRAL", or null for all.
     */
    val sentiment: String? = null,

    /**
     * Optional email category filter.
     */
    val emailCategory: EmailCategory? = null
) {
    /**
     * Check if file results should be included.
     */
    fun includeFiles(): Boolean = ContentType.FILE in contentTypes

    /**
     * Check if email results should be included.
     */
    fun includeEmails(): Boolean = ContentType.EMAIL in contentTypes

    /**
     * Check if OneDrive results should be included.
     */
    fun includeOneDrive(): Boolean = ContentType.ONEDRIVE in contentTypes

    /**
     * Check if chunk results should be included.
     */
    fun includeChunks(): Boolean = ContentType.CHUNK in contentTypes
}
