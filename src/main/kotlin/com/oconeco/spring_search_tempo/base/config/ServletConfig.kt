package com.oconeco.spring_search_tempo.base.config

import jakarta.servlet.SessionTrackingMode
import org.springframework.boot.web.servlet.ServletContextInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


@Configuration
class ServletConfig {

    @Bean
    fun servletContextInitializer(): ServletContextInitializer {
        // don't append the session id to resources
        return ServletContextInitializer { servletContext ->
                servletContext.setSessionTrackingModes(setOf(SessionTrackingMode.COOKIE)) }
    }

}
