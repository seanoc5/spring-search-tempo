package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.OffsetDateTime

/**
 * Persistent crawl resume marker. One row per CrawlConfig (per-config scope,
 * not per-startPath — the URI itself identifies which startPath we're inside).
 *
 * Written by [com.oconeco.spring_search_tempo.batch.fscrawl.CrawlCheckpointListener]
 * after each chunk and cleared on successful job completion. On startup, the
 * reader uses [lastProcessedUri] to skip everything already persisted in a
 * deterministic walk.
 */
@Entity
@Table(
    name = "crawl_checkpoint",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_crawl_checkpoint_config", columnNames = ["crawl_config_id"])
    ]
)
@EntityListeners(AuditingEntityListener::class)
class CrawlCheckpoint {

    @Id
    @SequenceGenerator(
        name = "crawl_checkpoint_sequence",
        sequenceName = "primary_sequence",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "crawl_checkpoint_sequence")
    @Column(nullable = false, updatable = false)
    var id: Long? = null

    @Column(name = "crawl_config_id", nullable = false)
    var crawlConfigId: Long? = null

    @Column(name = "last_processed_uri", nullable = false, columnDefinition = "text")
    var lastProcessedUri: String? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime? = null
}
