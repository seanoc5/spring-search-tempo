package com.oconeco.spring_search_tempo.base.config

import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Profiles

/**
 * Automatically activates OS-specific Spring profiles based on detected operating system.
 *
 * This EnvironmentPostProcessor runs early in the Spring Boot lifecycle to detect the
 * current operating system and activate the appropriate profile:
 * - Windows -> "windows" profile
 * - Linux -> "linux" profile
 * - macOS -> "mac" profile
 *
 * The activated profile determines which application-{profile}.yml configuration is loaded,
 * enabling OS-specific crawl configurations for filesystem differences.
 *
 * Usage:
 * - Automatically registered via META-INF/spring.factories
 * - No manual configuration required
 * - Can be overridden by explicitly setting spring.profiles.active
 */
class OsProfileActivator : EnvironmentPostProcessor {

    private val logger = LoggerFactory.getLogger(OsProfileActivator::class.java)

    enum class OsType(val profileName: String) {
        WINDOWS("windows"),
        LINUX("linux"),
        MAC("mac"),
        UNKNOWN("default")
    }

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        // Check if profiles are already explicitly set
        val activeProfiles = environment.activeProfiles
        if (activeProfiles.isNotEmpty()) {
            logger.info("Spring profiles already set: {}. Skipping OS detection.", activeProfiles.joinToString())
            return
        }

        // Detect OS and activate appropriate profile
        val osType = detectOsType()
        val profileToActivate = osType.profileName

        logger.info("Detected OS: {} -> Activating profile: {}", osType.name, profileToActivate)

        // Add the OS-specific profile to active profiles
        environment.addActiveProfile(profileToActivate)

        // Log system information for debugging
        logSystemInfo()
    }

    private fun detectOsType(): OsType {
        val osName = System.getProperty("os.name", "unknown").lowercase()

        return when {
            osName.contains("win") -> OsType.WINDOWS
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> OsType.LINUX
            osName.contains("mac") || osName.contains("darwin") -> OsType.MAC
            else -> OsType.UNKNOWN
        }
    }

    private fun logSystemInfo() {
        logger.info("=== System Information ===")
        logger.info("OS Name: {}", System.getProperty("os.name"))
        logger.info("OS Version: {}", System.getProperty("os.version"))
        logger.info("OS Arch: {}", System.getProperty("os.arch"))
        logger.info("User Home: {}", System.getProperty("user.home"))
        logger.info("Java Version: {}", System.getProperty("java.version"))
    }
}
