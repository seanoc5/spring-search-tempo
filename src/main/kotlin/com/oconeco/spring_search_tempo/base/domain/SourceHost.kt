package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.Table
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.OffsetDateTime

@Entity
@Table(name = "source_host")
@EntityListeners(AuditingEntityListener::class)
class SourceHost : SaveableObject() {

    @Column(nullable = false, unique = true, length = 100)
    var normalizedHost: String? = null

    @Column(length = 100)
    var displayName: String? = null

    @Column(length = 20)
    var osType: String? = null

    @Column(nullable = false)
    var enabled: Boolean = true

    @LastModifiedDate
    @Column(nullable = false)
    var lastSeenAt: OffsetDateTime? = null
}
