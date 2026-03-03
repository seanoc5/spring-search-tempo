package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.SequenceGenerator
import java.time.OffsetDateTime
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener


@Entity
@EntityListeners(AuditingEntityListener::class)
class SpringUser {

    @Id
    @Column(
        nullable = false,
        updatable = false
    )
    @SequenceGenerator(
        name = "spring_user_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(
        strategy = GenerationType.SEQUENCE,
        generator = "spring_user_sequence"
    )
    var id: Long? = null

    @Column(
        nullable = false,
        unique = true,
        columnDefinition = "text"
    )
    var label: String? = null

    @Column(columnDefinition = "text")
    var firstName: String? = null

    @Column(columnDefinition = "text")
    var lastName: String? = null

    @Column(columnDefinition = "text")
    var email: String? = null

    @Column
    var enabled: Boolean? = null

    @Column(
        nullable = false,
        columnDefinition = "text"
    )
    var password: String? = null

    @OneToMany(mappedBy = "springUser")
    var springRoles = mutableSetOf<SpringRole>()

    @CreatedDate
    @Column(
        nullable = false,
        updatable = false
    )
    var dateCreated: OffsetDateTime? = null

    @LastModifiedDate
    @Column(nullable = false)
    var lastUpdated: OffsetDateTime? = null

}
