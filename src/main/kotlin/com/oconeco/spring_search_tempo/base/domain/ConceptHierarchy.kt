package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.OneToMany
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import java.time.OffsetDateTime
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener

@Entity
@Table(
    name = "concept_hierarchy",
    indexes = [
        Index(name = "idx_concept_hierarchy_source_system", columnList = "source_system"),
        Index(name = "idx_concept_hierarchy_core", columnList = "core")
    ]
)
@EntityListeners(AuditingEntityListener::class)
class ConceptHierarchy {

    @Id
    @Column(nullable = false, updatable = false)
    @SequenceGenerator(
        name = "concept_hierarchy_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "concept_hierarchy_sequence")
    var id: Long? = null

    @Column(nullable = false, unique = true, length = 100)
    var code: String? = null

    @Column(nullable = false, unique = true, columnDefinition = "text")
    var uri: String? = null

    @Column(nullable = false, columnDefinition = "text")
    var label: String? = null

    @Column(columnDefinition = "text")
    var description: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 50)
    var sourceSystem: ConceptSourceSystem = ConceptSourceSystem.OCONECO

    @Column(nullable = false)
    var core: Boolean = false

    @Column(name = "supports_address", nullable = false)
    var supportsAddress: Boolean = false

    @Column(name = "expected_node_count")
    var expectedNodeCount: Long? = null

    @Column(name = "external_version", length = 100)
    var externalVersion: String? = null

    @Column(name = "last_imported_at")
    var lastImportedAt: OffsetDateTime? = null

    @CreatedDate
    @Column(name = "date_created", nullable = false, updatable = false)
    var dateCreated: OffsetDateTime? = null

    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    var lastUpdated: OffsetDateTime? = null

    @OneToMany(mappedBy = "hierarchy")
    var nodes = mutableSetOf<ConceptNode>()

}

enum class ConceptSourceSystem {
    OCONECO,
    OPENALEX
}
