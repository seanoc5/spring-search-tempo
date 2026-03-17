package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MappedSuperclass
import java.time.OffsetDateTime


@MappedSuperclass
abstract class FSObject : SaveableObject() {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_host_id")
    var sourceHostRef: SourceHost? = null

    /**
     * Stable ownership of this filesystem object.
     *
     * Unlike jobRunId, which tracks the last crawl run that touched the row,
     * crawlConfigId identifies which crawl configuration currently owns it.
     */
    @Column
    var crawlConfigId: Long? = null

    @Column(columnDefinition = "text")
    var owner: String? = null

    @Column(
        columnDefinition = "text",
        name = "\"group\""
    )
    var group: String? = null

    @Column(columnDefinition = "text")
    var permissions: String? = null

    @Column
    var fsLastModified: OffsetDateTime? = null

}
