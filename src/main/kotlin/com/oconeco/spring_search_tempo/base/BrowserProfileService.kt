package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.BrowserProfileDTO


interface BrowserProfileService {

    fun count(): Long

    fun findAll(): List<BrowserProfileDTO>

    fun findEnabled(): List<BrowserProfileDTO>

    fun get(id: Long): BrowserProfileDTO

    fun create(browserProfileDTO: BrowserProfileDTO): Long

    fun update(id: Long, browserProfileDTO: BrowserProfileDTO)

    fun delete(id: Long)

    fun profilePathExists(profilePath: String): Boolean

    /**
     * Find a profile by its file system path.
     */
    fun findByProfilePath(profilePath: String): BrowserProfileDTO?

    fun getBrowserProfileValues(): Map<Long, Long>

    /**
     * Update sync state after successful import.
     */
    fun updateSyncState(id: Long, bookmarkCount: Int)

    /**
     * Record an error that occurred during sync.
     */
    fun recordError(id: Long, error: String)

    /**
     * Clear the last error after successful sync.
     */
    fun clearError(id: Long)

}
