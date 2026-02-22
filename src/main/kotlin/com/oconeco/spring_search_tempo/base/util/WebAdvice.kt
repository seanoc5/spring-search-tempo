package com.oconeco.spring_search_tempo.base.util

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute


/**
 * Provide attributes available in all templates.
 */
@ControllerAdvice
class WebAdvice {

    @ModelAttribute("requestUri")
    fun getRequestUri(request: HttpServletRequest?): String = request?.requestURI ?: "/"

}

//
//@ControllerAdvice
//class GlobalAttributesAdvice {
//    @ModelAttribute("contextPath")
//    fun contextPath(request: HttpServletRequest): String {
//        return request.contextPath
//    }
//}