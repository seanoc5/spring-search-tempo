package com.oconeco.spring_search_tempo.base.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress

/**
 * Holds the configured host name so that @PrePersist callbacks
 * (which are not Spring-managed) can stamp sourceHost on new entities.
 *
 * Resolution order:
 * 1. TEMPO_HOST_NAME env var (via app.host-name property)
 * 2. Auto-detected from InetAddress.getLocalHost().hostName
 * 3. "unknown" if detection fails
 */
@Component
class HostNameHolder(@Value("\${app.host-name:localhost}") configuredName: String) {
    init {
        currentHostName = if (configuredName == "localhost") {
            resolveHostName()
        } else {
            configuredName
        }
        log.info("sourceHost resolved to: {}", currentHostName)
    }

    companion object {
        private val log = LoggerFactory.getLogger(HostNameHolder::class.java)

        @JvmStatic
        var currentHostName: String = "localhost"

        private fun resolveHostName(): String = try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            log.warn("Could not auto-detect hostname, falling back to 'unknown': {}", e.message)
            "unknown"
        }
    }
}
