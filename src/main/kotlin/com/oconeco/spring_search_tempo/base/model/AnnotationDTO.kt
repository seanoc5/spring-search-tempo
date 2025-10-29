package com.oconeco.spring_search_tempo.base.model

import jakarta.validation.constraints.NotNull


class AnnotationDTO {

    var id: Long? = null

    @NotNull
    @AnnotationLabelUnique
    var label: String? = null

    var description: String? = null

    var type: String? = null

}
