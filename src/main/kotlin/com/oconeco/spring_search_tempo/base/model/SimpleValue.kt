package com.oconeco.spring_search_tempo.base.model

import org.springframework.hateoas.EntityModel


class SimpleValue<T> private constructor(
    val `value`: T
) {


    companion object {

        @JvmStatic
        fun <T> entityModelOf(`value`: T): EntityModel<SimpleValue<T>> {
            val simpleValue = SimpleValue(value)
            return EntityModel.of(simpleValue)
        }

    }

}
