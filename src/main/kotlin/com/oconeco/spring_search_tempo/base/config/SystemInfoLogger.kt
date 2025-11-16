package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.service.OsDetectionService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Logs system and OS information when the application is ready.
 * Provides visibility into detected OS, filesystem types, and active profiles.
 */
@Component
class SystemInfoLogger(
    private val osDetectionService: OsDetectionService,
    private val environment: Environment
) {

    private val logger = LoggerFactory.getLogger(SystemInfoLogger::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun logSystemInfo() {
        logger.info("╔══════════════════════════════════════════════════════════════╗")
        logger.info("║          Spring Search Tempo - System Information           ║")
        logger.info("╚══════════════════════════════════════════════════════════════╝")

        // OS Detection
        osDetectionService.logSystemInfo()

        // Spring Profiles
        val activeProfiles = environment.activeProfiles
        logger.info("=== Spring Configuration ===")
        if (activeProfiles.isNotEmpty()) {
            logger.info("Active Profiles: {}", activeProfiles.joinToString(", "))
        } else {
            logger.info("Active Profiles: [default]")
        }

        // Crawl Configuration Source
        val configSource = when {
            activeProfiles.contains("windows") -> "application-windows.yml"
            activeProfiles.contains("linux") -> "application-linux.yml"
            activeProfiles.contains("mac") -> "application-mac.yml"
            else -> "application.yml (default)"
        }
        logger.info("Crawl Configuration Source: {}", configSource)

        logger.info("╚══════════════════════════════════════════════════════════════╝")
    }
}
