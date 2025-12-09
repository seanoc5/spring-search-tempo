package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany

/**
 * Persisted crawl configuration entity.
 * Stores crawl definitions in the database for UI management and job execution.
 */
@Entity
class CrawlConfig : SaveableObject() {

    @Column(
        nullable = false,
        unique = true,
        columnDefinition = "text"
    )
    var name: String? = null

    @Column(columnDefinition = "text")
    var displayLabel: String? = null

    @Column(nullable = false)
    var enabled: Boolean = true

    @Column(columnDefinition = "text[]")
    var startPaths: Array<String>? = null

    @Column
    var maxDepth: Int? = null

    @Column
    var followLinks: Boolean? = null

    @Column
    var parallel: Boolean? = null

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
    var filePatternsSkip: String? = null

    @Column(columnDefinition = "text")
    var filePatternsLocate: String? = null

    @Column(columnDefinition = "text")
    var filePatternsIndex: String? = null

    @Column(columnDefinition = "text")
    var filePatternsAnalyze: String? = null

    @OneToMany(mappedBy = "crawlConfig")
    var jobRuns: MutableSet<JobRun> = mutableSetOf()

}
