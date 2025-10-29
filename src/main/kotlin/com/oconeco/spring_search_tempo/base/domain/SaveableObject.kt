package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.Enumerated
import jakarta.persistence.EnumType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.SequenceGenerator
import java.time.OffsetDateTime
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener


@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class SaveableObject {

    @Id
    @Column(
        nullable = false,
        updatable = false
    )
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

    @Column(
        nullable = false,
        unique = true,
        columnDefinition = "text"
    )
    var uri: String? = null

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    var status: Status = Status.NEW

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    var analysisStatus: AnalysisStatus = AnalysisStatus.LOCATE

    @Column(columnDefinition = "text")
    var label: String? = null

    @Column(columnDefinition = "text")
    var description: String? = null

    @Column(columnDefinition = "text")
    var type: String? = null

    @Column
    var crawlDepth: Int? = null

    @Column
    var size: Long? = null

    @Column(nullable = false)
    var version: Long? = null

    @Column
    var archived: Boolean? = null

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

enum class Status {
    NEW,
    IN_PROGRESS,
    DIRTY,
    CURRENT,
    FAILED
}

enum class AnalysisStatus {
    SKIP,
    LOCATE,
    INDEX,
    ANALYZE,
    DEEP_ANALYZE
}