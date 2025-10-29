package com.oconeco.spring_search_tempo.base.model

import com.oconeco.spring_search_tempo.base.domain.Status
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime


class FSFolderDTO {

    var id: Long? = null

    @NotNull
    @FSFolderUriUnique
    var uri: String? = null

    var status: Status? = null

    var label: String? = null

    var description: String? = null

    var type: String? = null

    var crawlDepth: Int? = null

    var size: Long? = null

    @NotNull
    var version: Long? = null

    var archived: Boolean? = null

    var owner: String? = null

    var group: String? = null

    var permissions: String? = null

    var fsLastModified: OffsetDateTime? = null

}
