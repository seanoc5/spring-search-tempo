package com.oconeco.spring_search_tempo.base.model

import jakarta.validation.constraints.NotNull


class FSFileDTO {

    var id: Long? = null

    @NotNull
    @FSFileUriUnique
    var uri: String? = null

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

    var bodyText: String? = null

    var bodySize: Long? = null

    var fsFolder: Long? = null

}
