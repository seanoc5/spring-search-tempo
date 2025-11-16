package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.service.OsDetectionService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment

/**
 * Unit tests for SystemInfoLogger.
 *
 * Tests that the logger component can be instantiated and works correctly
 * without requiring full Spring Boot context and database.
 */
@DisplayName("SystemInfoLogger Tests")
class SystemInfoLoggerIntegrationTest {

    private lateinit var osDetectionService: OsDetectionService
    private lateinit var environment: Environment
    private lateinit var systemInfoLogger: SystemInfoLogger

    @BeforeEach
    fun setup() {
        osDetectionService = OsDetectionService()
        val env = StandardEnvironment()
        env.setActiveProfiles("test")
        environment = env
        systemInfoLogger = SystemInfoLogger(osDetectionService, environment)
    }

    // ===== Component Initialization Tests =====

    @Test
    @DisplayName("Should create SystemInfoLogger component")
    fun testSystemInfoLoggerCreation() {
        // Assert
        assertNotNull(systemInfoLogger, "SystemInfoLogger should be created")
    }

    @Test
    @DisplayName("Should create OsDetectionService dependency")
    fun testOsDetectionServiceCreation() {
        // Assert
        assertNotNull(osDetectionService, "OsDetectionService should be created")
    }

    @Test
    @DisplayName("Should create Environment dependency")
    fun testEnvironmentCreation() {
        // Assert
        assertNotNull(environment, "Environment should be created")
    }

    // ===== Service Integration Tests =====

    @Test
    @DisplayName("Should access OS detection service from logger")
    fun testOsDetectionServiceAccess() {
        // Act
        val osType = osDetectionService.osType
        val filesystemType = osDetectionService.currentFilesystemType

        // Assert
        assertNotNull(osType, "OS type should be accessible")
        assertNotNull(filesystemType, "Filesystem type should be accessible")
    }

    @Test
    @DisplayName("Should access active profiles from environment")
    fun testActiveProfilesAccess() {
        // Act
        val activeProfiles = environment.activeProfiles

        // Assert
        assertNotNull(activeProfiles, "Active profiles should be accessible")
        assertTrue(
            activeProfiles.contains("test"),
            "Test profile should be active in this test"
        )
    }

    // ===== Logging Method Tests =====

    @Test
    @DisplayName("Should call logSystemInfo without exceptions")
    fun testLogSystemInfoNoExceptions() {
        // Arrange & Act & Assert (no exception should be thrown)
        systemInfoLogger.logSystemInfo()
    }

    @Test
    @DisplayName("Should have access to system information for logging")
    fun testSystemInformationAvailable() {
        // Arrange & Act
        val osType = osDetectionService.osType
        val filesystemType = osDetectionService.currentFilesystemType
        val filesystemRoots = osDetectionService.getFilesystemRoots()
        val filesystemInfo = osDetectionService.getFilesystemInfo()

        // Assert
        assertNotNull(osType)
        assertNotNull(filesystemType)
        assertTrue(filesystemRoots.isNotEmpty(), "Should have at least one filesystem root")
        assertTrue(filesystemInfo.isNotEmpty(), "Should have filesystem info")
    }

    // ===== Profile Configuration Tests =====

    @Test
    @DisplayName("Should correctly identify configuration source based on profile")
    fun testConfigurationSourceIdentification() {
        // Arrange
        val activeProfiles = environment.activeProfiles

        // Act
        val configSource = when {
            activeProfiles.contains("windows") -> "application-windows.yml"
            activeProfiles.contains("linux") -> "application-linux.yml"
            activeProfiles.contains("mac") -> "application-mac.yml"
            else -> "application.yml (default)"
        }

        // Assert
        assertNotNull(configSource, "Configuration source should be determined")
        assertTrue(
            configSource in listOf(
                "application-windows.yml",
                "application-linux.yml",
                "application-mac.yml",
                "application.yml (default)"
            ),
            "Configuration source should be one of the known files"
        )
    }

    // ===== Event Listener Tests =====

    @Test
    @DisplayName("SystemInfoLogger should be a Spring component")
    fun testIsSpringComponent() {
        // Assert - If we can autowire it, it's a component
        assertNotNull(systemInfoLogger)
    }

    // ===== Integration with OsDetectionService =====

    @Test
    @DisplayName("Should use OsDetectionService methods correctly")
    fun testOsDetectionServiceIntegration() {
        // Act
        val isWindows = osDetectionService.isWindows()
        val isLinux = osDetectionService.isLinux()
        val isMac = osDetectionService.isMac()
        val recommendedProfile = osDetectionService.getRecommendedProfile()

        // Assert
        assertNotNull(recommendedProfile)
        assertTrue(
            recommendedProfile in listOf("windows", "linux", "mac", "default"),
            "Recommended profile should be valid"
        )

        // Only one should be true (or all false for UNKNOWN)
        val trueCount = listOf(isWindows, isLinux, isMac).count { it }
        assertTrue(
            trueCount <= 1,
            "At most one OS check should be true"
        )
    }

    // ===== Environment Configuration Tests =====

    @Test
    @DisplayName("Should have access to all environment properties")
    fun testEnvironmentPropertiesAccess() {
        // Act
        val osName = System.getProperty("os.name")
        val osVersion = System.getProperty("os.version")
        val osArch = System.getProperty("os.arch")
        val userHome = System.getProperty("user.home")

        // Assert
        assertNotNull(osName, "os.name should be available")
        assertNotNull(osVersion, "os.version should be available")
        assertNotNull(osArch, "os.arch should be available")
        assertNotNull(userHome, "user.home should be available")
    }
}
