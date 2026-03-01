package com.oconeco.spring_search_tempo.web.controller

import com.oconeco.spring_search_tempo.web.service.SystemMonitorService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/system")
class SystemMonitorController(
    private val systemMonitorService: SystemMonitorService
) {

    @GetMapping
    fun index(model: Model, request: HttpServletRequest): String {
        val monitor = systemMonitorService.getMonitorData()
        model.addAttribute("monitor", monitor)

        val isHtmx = request.getHeader("HX-Request") == "true"
        val isBoosted = request.getHeader("HX-Boosted") == "true"
        return if (isHtmx && !isBoosted) {
            "system/index :: monitor-content"
        } else {
            "system/index"
        }
    }

    /** HTMX polling endpoint - returns live metrics fragment every 10s. */
    @GetMapping("/live")
    fun liveMetrics(model: Model): String {
        val monitor = systemMonitorService.getLiveData()
        model.addAttribute("monitor", monitor)
        return "system/fragments/liveMetrics :: live-metrics"
    }
}
