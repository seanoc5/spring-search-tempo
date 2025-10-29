package com.oconeco.spring_search_tempo.base.model

import jakarta.validation.constraints.NotNull


class SpringRoleDTO {

    var id: Long? = null

    @NotNull
    @SpringRoleLabelUnique
    var label: String? = null

    var description: String? = null

    var springUser: Long? = null

}
