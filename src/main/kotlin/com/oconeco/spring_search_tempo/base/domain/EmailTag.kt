package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToMany
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.OffsetDateTime


/**
 * Tag entity for email messages.
 *
 * Supports user-defined tags plus protected system tags (like 'junk').
 * System tags cannot be deleted or renamed.
 */
@Entity
@Table(name = "email_tag")
@EntityListeners(AuditingEntityListener::class)
class EmailTag {

    @Id
    @Column(nullable = false, updatable = false)
    @SequenceGenerator(
        name = "primary_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1,
        initialValue = 10000
    )
    @GeneratedValue(
        strategy = GenerationType.SEQUENCE,
        generator = "primary_sequence"
    )
    var id: Long? = null

    /**
     * Unique tag name (case-sensitive).
     */
    @Column(nullable = false, unique = true, length = 100)
    var name: String? = null

    /**
     * Display color as hex code (e.g., '#dc3545' for red).
     */
    @Column(nullable = false, length = 7)
    var color: String = "#6c757d"

    /**
     * System tags (like 'junk') are protected from deletion/editing.
     */
    @Column(nullable = false)
    var isSystem: Boolean = false

    @ManyToMany(mappedBy = "tags")
    var messages: MutableSet<EmailMessage> = mutableSetOf()

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var dateCreated: OffsetDateTime? = null

    @LastModifiedDate
    @Column(nullable = false)
    var lastUpdated: OffsetDateTime? = null

}
