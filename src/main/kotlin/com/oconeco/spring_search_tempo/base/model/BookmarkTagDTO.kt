package com.oconeco.spring_search_tempo.base.model

import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime


class BookmarkTagDTO {

    var id: Long? = null

    @NotNull
    var name: String? = null

    var displayName: String? = null

    var usageCount: Int = 0

    var source: String? = null

    var dateCreated: OffsetDateTime? = null

    var lastUpdated: OffsetDateTime? = null

}
