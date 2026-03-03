package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import jakarta.persistence.SequenceGenerator
import java.time.OffsetDateTime
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener


/**
 * Tag entity for browser bookmarks.
 *
 * Firefox stores tags as special bookmark folders. This entity captures
 * those tags with a many-to-many relationship to bookmarks.
 */
@Entity
@Table(name = "bookmark_tag")
@EntityListeners(AuditingEntityListener::class)
class BookmarkTag {

    @Id
    @Column(nullable = false, updatable = false)
    @SequenceGenerator(
        name = "bookmark_tag_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(
        strategy = GenerationType.SEQUENCE,
        generator = "bookmark_tag_sequence"
    )
    var id: Long? = null

    /**
     * Normalized tag name (lowercase) for consistent matching.
     */
    @Column(nullable = false, unique = true, columnDefinition = "text")
    var name: String? = null

    /**
     * Original display name preserving case from the browser.
     */
    @Column(columnDefinition = "text")
    var displayName: String? = null

    /**
     * Denormalized count for quick "popular tags" queries.
     */
    @Column
    var usageCount: Int = 0

    /**
     * Source of the tag: FIREFOX, CHROME, USER (manually added).
     */
    @Column(columnDefinition = "text")
    var source: String? = null

    @ManyToMany(mappedBy = "tags")
    var bookmarks: MutableSet<BrowserBookmark> = mutableSetOf()

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var dateCreated: OffsetDateTime? = null

    @LastModifiedDate
    @Column(nullable = false)
    var lastUpdated: OffsetDateTime? = null

}
