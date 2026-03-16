package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.OneToMany
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate

/**
 * Persisted crawl configuration entity.
 * Stores crawl definitions in the database for UI management and job execution.
 */
@Entity
class CrawlConfig : SaveableObject() {

    @Column(
        nullable = false,
        columnDefinition = "text"
    )
    var name: String? = null

    @Column(columnDefinition = "text[]")
    var startPaths: Array<String>? = null

    @Column
    var maxDepth: Int? = null

    @Column
    var followLinks: Boolean? = null

    @Column
    var parallel: Boolean? = null

    @Column(nullable = false)
    var enabled: Boolean = true

    // Pattern storage as JSON text
    @Column(columnDefinition = "text")
    var folderPatternsSkip: String? = null

    @Column(columnDefinition = "text")
    var folderPatternsLocate: String? = null

    @Column(columnDefinition = "text")
    var folderPatternsIndex: String? = null

    @Column(columnDefinition = "text")
    var folderPatternsAnalyze: String? = null

    @Column(columnDefinition = "text")
    var folderPatternsSemantic: String? = null

    @Column(columnDefinition = "text")
    var filePatternsSkip: String? = null

    @Column(columnDefinition = "text")
    var filePatternsLocate: String? = null

    @Column(columnDefinition = "text")
    var filePatternsIndex: String? = null

    @Column(columnDefinition = "text")
    var filePatternsAnalyze: String? = null

    @Column(columnDefinition = "text")
    var filePatternsSemantic: String? = null

    @Column(nullable = false)
    var folderPrioritySkip: Int = 500

    @Column(nullable = false)
    var folderPrioritySemantic: Int = 400

    @Column(nullable = false)
    var folderPriorityAnalyze: Int = 300

    @Column(nullable = false)
    var folderPriorityIndex: Int = 200

    @Column(nullable = false)
    var folderPriorityLocate: Int = 100

    @Column(nullable = false)
    var filePrioritySkip: Int = 500

    @Column(nullable = false)
    var filePrioritySemantic: Int = 400

    @Column(nullable = false)
    var filePriorityAnalyze: Int = 300

    @Column(nullable = false)
    var filePriorityIndex: Int = 200

    @Column(nullable = false)
    var filePriorityLocate: Int = 100

    @OneToMany(mappedBy = "crawlConfig")
    var jobRuns: MutableSet<JobRun> = mutableSetOf()

    /**
     * Hours threshold for considering folders "recently crawled" by this config.
     * When another crawl encounters a folder that was crawled by this config
     * within this many hours, it can skip re-processing that subtree.
     * Default: null (use system default from app.crawl.defaults.recent-crawl-skip-hours)
     */
    @Column
    var freshnessHours: Int? = null

    // ============ Smart Crawl Scheduling ============

    /**
     * Enable temperature-based crawl scheduling for this config.
     * When false, all folders are crawled every session (legacy behavior).
     * When true, folders are prioritized based on their temperature tier.
     */
    @Column
    var smartCrawlEnabled: Boolean? = false

    /**
     * Days threshold for HOT temperature.
     * Folders modified within this many days are considered HOT.
     * Default: 7 days.
     */
    @Column
    var hotThresholdDays: Int? = null

    /**
     * Days threshold for WARM temperature.
     * Folders modified within this many days (but not HOT) are WARM.
     * Folders older than this threshold become COLD.
     * Default: 30 days.
     */
    @Column
    var warmThresholdDays: Int? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var crawlMode: CrawlMode = CrawlMode.ENFORCE

    @Column(nullable = false)
    var discoveryKeeperMaxDepth: Int = 20

    @Column(nullable = false)
    var discoverySkipMaxDepth: Int = 10

    @Column(nullable = false)
    var discoveryFileSampleCap: Int = 50

    @Column(nullable = false)
    var discoveryAutoSuggestEnabled: Boolean = true

    @PrePersist
    @PreUpdate
    fun normalizeSmartCrawlFields() {
        if (smartCrawlEnabled == null) {
            smartCrawlEnabled = false
        }
        if (discoveryKeeperMaxDepth <= 0) {
            discoveryKeeperMaxDepth = 20
        }
        if (discoverySkipMaxDepth <= 0) {
            discoverySkipMaxDepth = 10
        }
        if (discoveryFileSampleCap <= 0) {
            discoveryFileSampleCap = 50
        }
    }

}
