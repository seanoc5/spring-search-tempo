package com.oconeco.spring_search_tempo.base.util

import com.oconeco.spring_search_tempo.base.model.PaginationModel
import com.oconeco.spring_search_tempo.base.model.PaginationStep
import jakarta.servlet.http.HttpServletRequest
import java.lang.Math
import java.util.ArrayList
import org.springframework.context.MessageSource
import org.springframework.data.domain.Page
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.LocaleResolver


@Component
class WebUtils(
    messageSource: MessageSource,
    localeResolver: LocaleResolver
) {

    init {
        WebUtils.messageSource = messageSource
        WebUtils.localeResolver = localeResolver
    }

    companion object {

        const val MSG_SUCCESS = "MSG_SUCCESS"

        const val MSG_INFO = "MSG_INFO"

        const val MSG_ERROR = "MSG_ERROR"

        lateinit var messageSource: MessageSource

        lateinit var localeResolver: LocaleResolver

        @JvmStatic
        fun getRequest(): HttpServletRequest = (RequestContextHolder.getRequestAttributes() as
                ServletRequestAttributes).request

        @JvmStatic
        fun getMessage(code: String, vararg args: Any?): String? = messageSource.getMessage(code,
                args, code, localeResolver.resolveLocale(getRequest()))

        @JvmStatic
        private fun getStepUrl(page: Page<*>, targetPage: Int): String {
            var stepUrl = "?page=${targetPage}&size=${page.size}"
            if (getRequest().getParameter("sort") != null) {
                stepUrl += "&sort=" + getRequest().getParameter("sort")
            }
            if (getRequest().getParameter("filter") != null) {
                stepUrl += "&filter=" + getRequest().getParameter("filter")
            }
            return stepUrl
        }

        @JvmStatic
        fun getPaginationModel(page: Page<*>): PaginationModel? {
            if (page.isEmpty) {
                return null
            }

            val steps: ArrayList<PaginationStep> = ArrayList()
            val previous = PaginationStep()
            previous.disabled = !page.hasPrevious()
            previous.label = getMessage("pagination.previous")
            previous.url = getStepUrl(page, page.previousOrFirstPageable().pageNumber)
            steps.add(previous)
            // find a range of up to 5 pages around the current active page
            val startAt = Math.max(0, Math.min(page.number - 2, page.totalPages - 5))
            val endAt = Math.min(startAt + 5, page.totalPages)
            for (i in startAt until endAt) {
                val step = PaginationStep()
                step.active = i == page.number
                step.label = "" + (i + 1)
                step.url = getStepUrl(page, i)
                steps.add(step)
            }
            val next = PaginationStep()
            next.disabled = !page.hasNext()
            next.label = getMessage("pagination.next")
            next.url = getStepUrl(page, page.nextOrLastPageable().pageNumber)
            steps.add(next)

            val rangeStart = page.number * page.size + 1L
            val rangeEnd = Math.min(rangeStart + page.size - 1, page.totalElements)
            val range = if (rangeStart == rangeEnd) "" + rangeStart else
                    "${rangeStart} - ${rangeEnd}"
            val paginationModel = PaginationModel()
            paginationModel.steps = steps
            paginationModel.elements = getMessage("pagination.elements", range, page.totalElements)
            return paginationModel
        }

    }

}
