package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.OneToMany
import java.time.OffsetDateTime
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes


@Entity
class FSFolder : FSObject() {

    // ============ Smart Crawl Scheduling Fields ============

    /**
     * When this folder was last fully crawled.
     * Used to determine if the folder is due for re-crawling based on temperature.
     */
    @Column
    var lastCrawledAt: OffsetDateTime? = null

    /**
     * Crawl temperature tier: HOT, WARM, or COLD.
     * Determines how frequently the folder should be crawled.
     * New folders default to WARM (crawl daily).
     */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var crawlTemperature: CrawlTemperature = CrawlTemperature.WARM

    /**
     * Most recent child file modification detected in this folder.
     * Used to calculate temperature based on activity recency.
     */
    @Column
    var childModifiedAt: OffsetDateTime? = null

    /**
     * Rolling activity score: +10 for changes detected, -1 per crawl with no changes.
     * Helps identify consistently active folders vs. one-time bursts.
     * Range: 0 to 100.
     */
    @Column(nullable = false)
    var changeScore: Int = 0

    /**
     * Pattern stability score: how stable are the classification patterns for this folder?
     * Fed from discovery observation statistics (reapplyChangedCount over recent runs).
     * 0 = unstable patterns (should crawl more often to refine)
     * 100 = stable patterns (can safely cool to COLD temperature)
     * Default: 50 (neutral, no stability data yet)
     */
    @Column(nullable = false)
    var patternStabilityScore: Int = 50

    /**
     * Baseline manifest JSON snapshot used by CrawlConfig validation UI.
     * Stores a capped sample of file metadata as starter test data.
     */
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    var baselineManifest: String? = null

    @Column
    var baselineCapturedAt: OffsetDateTime? = null

    @Column
    var baselineSourceJobRunId: Long? = null

    @Column
    var baselineTotalFiles: Int? = null

    @Column
    var baselineSampleFiles: Int? = null

    @Column(length = 64)
    var baselineSamplingPolicy: String? = null

    @Column(length = 64)
    var baselineSeed: String? = null

    @Column(nullable = false)
    var baselineVersion: Int = 1

    @OneToMany(mappedBy = "fsFolder")
    var fsFiles = mutableSetOf<FSFile>()

}
