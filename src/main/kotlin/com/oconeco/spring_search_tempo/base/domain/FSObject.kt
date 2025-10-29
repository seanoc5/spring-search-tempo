package com.oconeco.spring_search_tempo.base.domain

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.OffsetDateTime


@MappedSuperclass
abstract class FSObject : SaveableObject() {

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
