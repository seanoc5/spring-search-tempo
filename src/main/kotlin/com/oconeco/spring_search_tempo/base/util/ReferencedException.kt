package com.oconeco.spring_search_tempo.base.util

import java.lang.RuntimeException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus


@ResponseStatus(HttpStatus.CONFLICT)
class ReferencedException : RuntimeException() {

    var key: String? = null

    var params = mutableListOf<Any>()

    override val message: String?
        get() {
            var message = key!!
            if (params.isNotEmpty()) {
                message += "," + params.joinToString(",")
            }
            return message
        }

    fun addParam(`param`: Any?) {
        params.add(param!!)
    }

}
