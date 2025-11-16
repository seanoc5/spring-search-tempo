package com.oconeco.spring_search_tempo.base.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for OsDetectionService.
 *
 * Tests OS and filesystem detection capabilities without requiring Spring context.
 */
@DisplayName("OsDetectionService Tests")
class OsDetectionServiceTest {

    private lateinit var service: OsDetectionService

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        service = OsDetectionService()
    }

    // ===== OS Type Detection Tests =====

    @Test
    @DisplayName("Should detect OS type based on system property")
    fun testOsTypeDetection() {
        // Arrange & Act
        val osType = service.osType

        // Assert
        assertNotNull(osType, "OS type should be detected")
        assertTrue(
            osType in listOf(
                OsDetectionService.OsType.WINDOWS,
                OsDetectionService.OsType.LINUX,
                OsDetectionService.OsType.MAC,
                OsDetectionService.OsType.UNKNOWN
            ),
            "OS type should be one of the known types"
        )
    }

    @Test
    @DisplayName("Should detect current OS correctly based on os.name property")
    fun testCurrentOsDetection() {
        // Arrange
        val osName = System.getProperty("os.name").lowercase()

        // Act
        val osType = service.osType

        // Assert
        when {
            osName.contains("win") -> {
                assertEquals(OsDetectionService.OsType.WINDOWS, osType)
                assertTrue(service.isWindows())
                assertFalse(service.isLinux())
                assertFalse(service.isMac())
            }
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> {
                assertEquals(OsDetectionService.OsType.LINUX, osType)
                assertTrue(service.isLinux())
                assertFalse(service.isWindows())
                assertFalse(service.isMac())
            }
            osName.contains("mac") || osName.contains("darwin") -> {
                assertEquals(OsDetectionService.OsType.MAC, osType)
                assertTrue(service.isMac())
                assertFalse(service.isWindows())
                assertFalse(service.isLinux())
            }
        }
    }

    @Test
    @DisplayName("Should cache OS type (lazy evaluation)")
    fun testOsTypeCaching() {
        // Arrange & Act
        val firstCall = service.osType
        val secondCall = service.osType

        // Assert
        assertEquals(firstCall, secondCall, "OS type should be cached and consistent")
    }

    // ===== Filesystem Type Detection Tests =====

    @Test
    @DisplayName("Should detect filesystem type for temp directory")
    fun testFilesystemTypeDetection() {
        // Arrange & Act
        val fsType = service.detectFilesystemType(tempDir)

        // Assert
        assertNotNull(fsType, "Filesystem type should be detected")
        assertTrue(
            fsType in listOf(
                OsDetectionService.FilesystemType.NTFS,
                OsDetectionService.FilesystemType.FAT32,
                OsDetectionService.FilesystemType.EXT4,
                OsDetectionService.FilesystemType.EXT3,
                OsDetectionService.FilesystemType.XFS,
                OsDetectionService.FilesystemType.BTRFS,
                OsDetectionService.FilesystemType.APFS,
                OsDetectionService.FilesystemType.HFS_PLUS,
                OsDetectionService.FilesystemType.UNKNOWN
            ),
            "Filesystem type should be one of the known types"
        )
    }

    @Test
    @DisplayName("Should detect filesystem type for working directory")
    fun testCurrentFilesystemType() {
        // Arrange & Act
        val fsType = service.currentFilesystemType

        // Assert
        assertNotNull(fsType, "Current filesystem type should be detected")
    }

    @Test
    @DisplayName("Should cache current filesystem type (lazy evaluation)")
    fun testCurrentFilesystemTypeCaching() {
        // Arrange & Act
        val firstCall = service.currentFilesystemType
        val secondCall = service.currentFilesystemType

        // Assert
        assertEquals(firstCall, secondCall, "Current filesystem type should be cached")
    }

    @Test
    @DisplayName("Should handle invalid path gracefully")
    fun testInvalidPathFilesystemDetection() {
        // Arrange
        val invalidPath = Path.of("/this/path/definitely/does/not/exist/anywhere")

        // Act
        val fsType = service.detectFilesystemType(invalidPath)

        // Assert
        assertEquals(
            OsDetectionService.FilesystemType.UNKNOWN,
            fsType,
            "Should return UNKNOWN for invalid paths"
        )
    }

    // ===== Filesystem Roots Tests =====

    @Test
    @DisplayName("Should get filesystem roots")
    fun testGetFilesystemRoots() {
        // Arrange & Act
        val roots = service.getFilesystemRoots()

        // Assert
        assertNotNull(roots, "Filesystem roots should not be null")
        assertTrue(roots.isNotEmpty(), "Should have at least one filesystem root")

        // Verify based on OS
        when (service.osType) {
            OsDetectionService.OsType.WINDOWS -> {
                assertTrue(
                    roots.any { it.toString().matches(Regex("[A-Z]:\\\\")) },
                    "Windows should have drive letters (C:\\, D:\\, etc.)"
                )
            }
            OsDetectionService.OsType.LINUX, OsDetectionService.OsType.MAC -> {
                assertTrue(
                    roots.any { it.toString() == "/" },
                    "Unix-like systems should have root /"
                )
            }
            else -> {
                // Unknown OS - just verify we got roots
                assertTrue(roots.isNotEmpty())
            }
        }
    }

    @Test
    @DisplayName("Should get filesystem info for all roots")
    fun testGetFilesystemInfo() {
        // Arrange & Act
        val filesystemInfo = service.getFilesystemInfo()

        // Assert
        assertNotNull(filesystemInfo, "Filesystem info should not be null")
        assertTrue(filesystemInfo.isNotEmpty(), "Should have info for at least one root")

        // Verify structure
        filesystemInfo.forEach { (root, fsType) ->
            assertNotNull(root, "Root path should not be null")
            assertNotNull(fsType, "Filesystem type should not be null")
        }
    }

    // ===== Helper Method Tests =====

    @Test
    @DisplayName("Should provide correct recommended profile based on OS")
    fun testGetRecommendedProfile() {
        // Arrange & Act
        val profile = service.getRecommendedProfile()

        // Assert
        assertNotNull(profile, "Recommended profile should not be null")

        when (service.osType) {
            OsDetectionService.OsType.WINDOWS -> assertEquals("windows", profile)
            OsDetectionService.OsType.LINUX -> assertEquals("linux", profile)
            OsDetectionService.OsType.MAC -> assertEquals("mac", profile)
            OsDetectionService.OsType.UNKNOWN -> assertEquals("default", profile)
        }
    }

    @Test
    @DisplayName("Should have mutually exclusive OS type checks")
    fun testMutuallyExclusiveOsChecks() {
        // Arrange
        val checks = listOf(service.isWindows(), service.isLinux(), service.isMac())

        // Act
        val trueCount = checks.count { it }

        // Assert
        assertTrue(
            trueCount <= 1,
            "At most one OS check should be true (could be 0 for UNKNOWN)"
        )
    }

    // ===== Logging Test (verify no exceptions) =====

    @Test
    @DisplayName("Should log system info without exceptions")
    fun testLogSystemInfo() {
        // Arrange & Act & Assert (no exception should be thrown)
        service.logSystemInfo()
    }

    // ===== Consistency Tests =====

    @Test
    @DisplayName("Should have consistent OS detection across methods")
    fun testConsistentOsDetection() {
        // Arrange & Act
        val osType = service.osType
        val isWindows = service.isWindows()
        val isLinux = service.isLinux()
        val isMac = service.isMac()

        // Assert - verify consistency
        when (osType) {
            OsDetectionService.OsType.WINDOWS -> {
                assertTrue(isWindows, "isWindows() should be true when osType is WINDOWS")
                assertFalse(isLinux, "isLinux() should be false when osType is WINDOWS")
                assertFalse(isMac, "isMac() should be false when osType is WINDOWS")
            }
            OsDetectionService.OsType.LINUX -> {
                assertTrue(isLinux, "isLinux() should be true when osType is LINUX")
                assertFalse(isWindows, "isWindows() should be false when osType is LINUX")
                assertFalse(isMac, "isMac() should be false when osType is LINUX")
            }
            OsDetectionService.OsType.MAC -> {
                assertTrue(isMac, "isMac() should be true when osType is MAC")
                assertFalse(isWindows, "isWindows() should be false when osType is MAC")
                assertFalse(isLinux, "isLinux() should be false when osType is MAC")
            }
            OsDetectionService.OsType.UNKNOWN -> {
                assertFalse(isWindows, "isWindows() should be false when osType is UNKNOWN")
                assertFalse(isLinux, "isLinux() should be false when osType is UNKNOWN")
                assertFalse(isMac, "isMac() should be false when osType is UNKNOWN")
            }
        }
    }
}
