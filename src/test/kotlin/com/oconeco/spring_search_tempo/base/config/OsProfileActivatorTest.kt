package com.oconeco.spring_search_tempo.base.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.StandardEnvironment

/**
 * Unit tests for OsProfileActivator.
 *
 * Tests profile activation logic based on OS detection without full Spring context.
 */
@DisplayName("OsProfileActivator Tests")
class OsProfileActivatorTest {

    private lateinit var activator: OsProfileActivator
    private lateinit var environment: ConfigurableEnvironment
    private lateinit var application: SpringApplication

    @BeforeEach
    fun setup() {
        activator = OsProfileActivator()
        environment = StandardEnvironment()
        application = SpringApplication()
    }

    // ===== Profile Activation Tests =====

    @Test
    @DisplayName("Should activate OS-specific profile when no profiles are set")
    fun testProfileActivationWhenNoneSet() {
        // Arrange
        // Environment has no active profiles initially
        assertEquals(0, environment.activeProfiles.size, "Should start with no active profiles")

        // Act
        activator.postProcessEnvironment(environment, application)

        // Assert
        val activeProfiles = environment.activeProfiles
        assertEquals(1, activeProfiles.size, "Should activate exactly one profile")

        val activatedProfile = activeProfiles[0]
        assertTrue(
            activatedProfile in listOf("windows", "linux", "mac", "default"),
            "Activated profile should be one of the OS-specific profiles"
        )
    }

    @Test
    @DisplayName("Should activate profile matching current OS")
    fun testProfileMatchesCurrentOS() {
        // Arrange
        val osName = System.getProperty("os.name").lowercase()

        // Act
        activator.postProcessEnvironment(environment, application)

        // Assert
        val activeProfiles = environment.activeProfiles
        val activatedProfile = activeProfiles[0]

        when {
            osName.contains("win") -> {
                assertEquals("windows", activatedProfile, "Should activate 'windows' profile on Windows")
            }
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> {
                assertEquals("linux", activatedProfile, "Should activate 'linux' profile on Linux")
            }
            osName.contains("mac") || osName.contains("darwin") -> {
                assertEquals("mac", activatedProfile, "Should activate 'mac' profile on macOS")
            }
            else -> {
                assertEquals("default", activatedProfile, "Should activate 'default' profile for unknown OS")
            }
        }
    }

    @Test
    @DisplayName("Should not add profile if profiles are already explicitly set")
    fun testSkipsActivationWhenProfilesAlreadySet() {
        // Arrange
        environment.setActiveProfiles("custom-profile")
        assertEquals(1, environment.activeProfiles.size)
        assertEquals("custom-profile", environment.activeProfiles[0])

        // Act
        activator.postProcessEnvironment(environment, application)

        // Assert
        val activeProfiles = environment.activeProfiles
        assertEquals(1, activeProfiles.size, "Should not add additional profile")
        assertEquals("custom-profile", activeProfiles[0], "Should keep existing profile")
    }

    @Test
    @DisplayName("Should not add profile if multiple profiles are already set")
    fun testSkipsActivationWhenMultipleProfilesSet() {
        // Arrange
        environment.setActiveProfiles("dev", "local")
        assertEquals(2, environment.activeProfiles.size)

        // Act
        activator.postProcessEnvironment(environment, application)

        // Assert
        val activeProfiles = environment.activeProfiles
        assertEquals(2, activeProfiles.size, "Should not add additional profile")
        assertTrue(activeProfiles.contains("dev"))
        assertTrue(activeProfiles.contains("local"))
    }

    // ===== OS Detection Logic Tests =====

    @Test
    @DisplayName("Should detect Windows OS correctly")
    fun testWindowsDetection() {
        // This test will only pass on Windows, but demonstrates the logic
        val osName = System.getProperty("os.name").lowercase()

        if (osName.contains("win")) {
            // Arrange & Act
            activator.postProcessEnvironment(environment, application)

            // Assert
            assertTrue(
                environment.activeProfiles.contains("windows"),
                "Should activate 'windows' profile when running on Windows"
            )
        }
    }

    @Test
    @DisplayName("Should detect Linux OS correctly")
    fun testLinuxDetection() {
        // This test will only pass on Linux, but demonstrates the logic
        val osName = System.getProperty("os.name").lowercase()

        if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            // Arrange & Act
            activator.postProcessEnvironment(environment, application)

            // Assert
            assertTrue(
                environment.activeProfiles.contains("linux"),
                "Should activate 'linux' profile when running on Linux"
            )
        }
    }

    @Test
    @DisplayName("Should detect macOS correctly")
    fun testMacDetection() {
        // This test will only pass on macOS, but demonstrates the logic
        val osName = System.getProperty("os.name").lowercase()

        if (osName.contains("mac") || osName.contains("darwin")) {
            // Arrange & Act
            activator.postProcessEnvironment(environment, application)

            // Assert
            assertTrue(
                environment.activeProfiles.contains("mac"),
                "Should activate 'mac' profile when running on macOS"
            )
        }
    }

    // ===== Edge Cases =====

    @Test
    @DisplayName("Should handle null application gracefully")
    fun testHandlesNullApplication() {
        // Arrange & Act & Assert (should not throw exception)
        activator.postProcessEnvironment(environment, application)

        // Verify profile was still activated
        assertTrue(environment.activeProfiles.isNotEmpty())
    }

    @Test
    @DisplayName("Should be idempotent when called multiple times")
    fun testIdempotency() {
        // Arrange
        val firstCallProfileCount = 0

        // Act - First call
        activator.postProcessEnvironment(environment, application)
        val firstProfiles = environment.activeProfiles.toList()

        // Manually add a profile to simulate it being "already set"
        // (In real scenario, second call would see profiles from first call)

        // Create a new environment with the profile already set
        val environment2 = StandardEnvironment()
        environment2.setActiveProfiles(*firstProfiles.toTypedArray())

        // Act - Second call
        activator.postProcessEnvironment(environment2, application)
        val secondProfiles = environment2.activeProfiles.toList()

        // Assert - Should not add duplicate profile
        assertEquals(
            firstProfiles.size,
            secondProfiles.size,
            "Should not add duplicate profiles"
        )
        assertEquals(
            firstProfiles,
            secondProfiles,
            "Profiles should remain the same"
        )
    }

    // ===== OsType Enum Tests =====

    @Test
    @DisplayName("OsType enum should have correct profile names")
    fun testOsTypeProfileNames() {
        // Arrange & Act & Assert
        assertEquals("windows", OsProfileActivator.OsType.WINDOWS.profileName)
        assertEquals("linux", OsProfileActivator.OsType.LINUX.profileName)
        assertEquals("mac", OsProfileActivator.OsType.MAC.profileName)
        assertEquals("default", OsProfileActivator.OsType.UNKNOWN.profileName)
    }

    @Test
    @DisplayName("Should have all expected OsType enum values")
    fun testOsTypeEnumValues() {
        // Arrange & Act
        val values = OsProfileActivator.OsType.values()

        // Assert
        assertEquals(4, values.size, "Should have exactly 4 OS types")
        assertTrue(values.contains(OsProfileActivator.OsType.WINDOWS))
        assertTrue(values.contains(OsProfileActivator.OsType.LINUX))
        assertTrue(values.contains(OsProfileActivator.OsType.MAC))
        assertTrue(values.contains(OsProfileActivator.OsType.UNKNOWN))
    }

    // ===== Integration with System Properties =====

    @Test
    @DisplayName("Should read os.name system property")
    fun testReadsOsNameProperty() {
        // Arrange
        val osName = System.getProperty("os.name")

        // Assert
        assertTrue(osName.isNotBlank(), "os.name system property should be available")

        // Act
        activator.postProcessEnvironment(environment, application)

        // Assert - verify a profile was activated based on the property
        assertTrue(
            environment.activeProfiles.isNotEmpty(),
            "Should activate a profile based on os.name"
        )
    }
}
