package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.OneToMany
import java.time.OffsetDateTime


/**
 * Browser profile configuration and sync state.
 *
 * Represents a browser profile source (e.g., Firefox default-release profile)
 * for importing bookmarks. Tracks sync progress for incremental imports.
 */
@Entity
class BrowserProfile : SaveableObject() {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var browserType: BrowserType? = null

    @Column(columnDefinition = "text")
    var profileName: String? = null

    @Column(columnDefinition = "text")
    var profilePath: String? = null

    @Column(columnDefinition = "text")
    var placesDbPath: String? = null

    @Column
    var lastSyncAt: OffsetDateTime? = null

    @Column
    var lastSyncBookmarkCount: Int? = null

    @Column
    var enabled: Boolean = true

    @Column(columnDefinition = "text")
    var lastError: String? = null

    @Column
    var lastErrorAt: OffsetDateTime? = null

    @OneToMany(mappedBy = "browserProfile")
    var bookmarks: MutableSet<BrowserBookmark> = mutableSetOf()

}

/**
 * Supported browser types.
 */
enum class BrowserType {
    FIREFOX,
    CHROME,   // Future
    EDGE      // Future
}
