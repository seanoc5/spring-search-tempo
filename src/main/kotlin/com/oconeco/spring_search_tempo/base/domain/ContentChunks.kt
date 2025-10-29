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
import jakarta.persistence.OneToMany
import jakarta.persistence.SequenceGenerator
import java.time.OffsetDateTime
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener


@Entity
@EntityListeners(AuditingEntityListener::class)
class ContentChunks {

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

    @Column(nullable = false)
    var chunkNumber: Int? = null

    @Column
    var chunkType: String? = null

    @Column
    var createdAt: OffsetDateTime? = null

    @Column
    var endPosition: Long? = null

    @Column
    var ftsVector: String? = null

    @Column
    var lastModifiedDate: OffsetDateTime? = null

    @Column(columnDefinition = "text")
    var namedEntities: String? = null

    @Column(columnDefinition = "text")
    var nouns: String? = null

    @Column
    var parentType: String? = null

    @Column
    var startPosition: Long? = null

    @Column(
        nullable = false,
        columnDefinition = "text"
    )
    var text: String? = null

    @Column(columnDefinition = "text")
    var tokenAnnotations: String? = null

    @Column
    var vectorEmbedding: String? = null

    @Column(columnDefinition = "text")
    var verbs: String? = null

    @Column
    var solrIndexedAt: OffsetDateTime? = null

    @Column
    var textLength: Long? = null

    @Column(columnDefinition = "text")
    var userAnnotations: String? = null

    @Column
    var status: String? = null

    @Column(columnDefinition = "text")
    var parseNpvp: String? = null

    @Column(columnDefinition = "text")
    var parseUd: String? = null

    @Column(columnDefinition = "text")
    var parseTree: String? = null

    @Column(columnDefinition = "text")
    var conllu: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_chunk_id")
    var parentChunk: ContentChunks? = null

    @OneToMany(mappedBy = "parentChunk")
    var parentChunkContentChunkses = mutableSetOf<ContentChunks>()

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concept_id")
    var concept: FSFile? = null

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
