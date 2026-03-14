package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener

@Entity
@Table(
    name = "concept_node",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_concept_node_hierarchy_external_key",
            columnNames = ["hierarchy_id", "external_key"]
        ),
        UniqueConstraint(
            name = "uk_concept_node_hierarchy_uri",
            columnNames = ["hierarchy_id", "uri"]
        )
    ],
    indexes = [
        Index(name = "idx_concept_node_hierarchy", columnList = "hierarchy_id"),
        Index(name = "idx_concept_node_parent", columnList = "parent_id"),
        Index(name = "idx_concept_node_address", columnList = "address"),
        Index(name = "idx_concept_node_wikidata", columnList = "wikidata_id")
    ]
)
@EntityListeners(AuditingEntityListener::class)
class ConceptNode {

    @Id
    @Column(nullable = false, updatable = false)
    @SequenceGenerator(
        name = "concept_node_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "concept_node_sequence")
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hierarchy_id", nullable = false)
    var hierarchy: ConceptHierarchy? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: ConceptNode? = null

    @OneToMany(mappedBy = "parent")
    var children = mutableSetOf<ConceptNode>()

    @Column(name = "external_key", nullable = false, length = 255)
    var externalKey: String? = null

    @Column(nullable = false, columnDefinition = "text")
    var uri: String? = null

    @Column(nullable = false, columnDefinition = "text")
    var label: String? = null

    @Column(columnDefinition = "text")
    var description: String? = null

    @Column(length = 1024)
    var address: String? = null

    @Column(name = "path", columnDefinition = "text")
    var path: String? = null

    @Column(name = "wikidata_id", length = 64)
    var wikidataId: String? = null

    @Column(name = "openalex_id", length = 128)
    var openAlexId: String? = null

    @Column(name = "depth_level")
    var depthLevel: Int? = null

    @Column(nullable = false)
    var leaf: Boolean = false

    @Column(nullable = false)
    var active: Boolean = true

    @CreatedDate
    @Column(name = "date_created", nullable = false, updatable = false)
    var dateCreated: OffsetDateTime? = null

    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    var lastUpdated: OffsetDateTime? = null

}
