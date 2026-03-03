package com.oconeco.spring_search_tempo.base.domain

import com.oconeco.spring_search_tempo.base.config.HostNameHolder
import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.Enumerated
import jakarta.persistence.EnumType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
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
        allocationSize = 1
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
    var status: Status? = Status.NEW

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    var analysisStatus: AnalysisStatus? = AnalysisStatus.LOCATE

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
    var version: Long? = 0L

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

    @Column
    var jobRunId: Long? = null

    @Column(length = 50)
    var sourceHost: String? = null

    /**
     * When filesystem metadata was last synced during discovery.
     * Used to track when the item was last "located" in the filesystem.
     */
    @Column
    var locatedAt: OffsetDateTime? = null

    /**
     * True if a SKIP pattern matched during discovery phase.
     * This flag is set before full pattern assignment runs, enabling
     * SKIP_SUBTREE optimization (children never enumerated).
     */
    @Column
    var skipDetected: Boolean? = null

    /**
     * Explains why the current analysisStatus was assigned.
     * Examples:
     * - "PATTERN: .*\.git.*" (matched a SKIP folder pattern)
     * - "MANUAL: User request via API" (manually overridden)
     * - "INHERITED: parent folder" (inherited from parent's status)
     */
    @Column(columnDefinition = "text")
    var analysisStatusReason: String? = null

    /**
     * Who/what assigned the current analysisStatus.
     * Values: PATTERN, MANUAL, INHERITED, DEFAULT
     */
    @Column(columnDefinition = "text")
    var analysisStatusSetBy: String? = null

    @PrePersist
    fun prePersistSourceHost() {
        if (sourceHost == null) {
            sourceHost = HostNameHolder.currentHostName
        }
    }

}

enum class Status {
    NEW,
    IN_PROGRESS,
    DIRTY,
    CURRENT,
    FAILED
}

/**
 * Analysis status levels for file system objects.
 *
 * - SKIP: Item matched skip pattern - metadata persisted but no further processing
 * - LOCATE: Metadata only (like plocate) - path, size, timestamps
 * - INDEX: Text extraction and full-text search indexing
 * - ANALYZE: INDEX + NLP processing (NER, POS, sentiment, parsing)
 * - SEMANTIC: Reserved for future vector embedding support (Phase 3)
 */
enum class AnalysisStatus {
    SKIP,
    LOCATE,
    INDEX,
    ANALYZE,
    SEMANTIC
}
