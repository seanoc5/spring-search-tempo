package com.oconeco.spring_search_tempo.base

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody


@Controller
class SessionTestController {

    @GetMapping("/sessionCreate")
    @ResponseBody
    fun sessionCreate(session: HttpSession) {
        session.setAttribute("testAttr", "test")
    }

    @GetMapping("/sessionRead")
    @ResponseBody
    fun sessionRead(session: HttpSession): String? = (session.getAttribute("testAttr") as String?)

}
