package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.OffsetDateTime

/**
 * Maps a SpringUser to a sourceHost string for ownership/visibility filtering.
 *
 * A user can own multiple sourceHosts, and this enables "default mine only"
 * visibility where users see only their own resources (CrawlConfigs, files, etc.)
 * that match their owned sourceHosts.
 */
@Entity
@Table(
    name = "user_source_host",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_source_host",
            columnNames = ["spring_user_id", "source_host"]
        )
    ]
)
@EntityListeners(AuditingEntityListener::class)
class UserSourceHost {

    @Id
    @Column(nullable = false, updatable = false)
    @SequenceGenerator(
        name = "user_source_host_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(
        strategy = GenerationType.SEQUENCE,
        generator = "user_source_host_sequence"
    )
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spring_user_id", nullable = false)
    var springUser: SpringUser? = null

    @Column(name = "source_host", nullable = false, length = 50)
    var sourceHost: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_host_id")
    var sourceHostRef: SourceHost? = null

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var dateCreated: OffsetDateTime? = null
}
