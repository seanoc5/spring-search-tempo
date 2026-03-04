package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany
import java.time.OffsetDateTime
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes


@Entity
class FSFolder : FSObject() {

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
