package com.oconeco.spring_search_tempo.base.model

import jakarta.validation.constraints.NotNull


class SpringUserDTO {

    var id: Long? = null

    @NotNull
    @SpringUserLabelUnique
    var label: String? = null

    var firstName: String? = null

    var lastName: String? = null

    var email: String? = null

    var enabled: Boolean? = null

    @NotNull
    var password: String? = null

}
