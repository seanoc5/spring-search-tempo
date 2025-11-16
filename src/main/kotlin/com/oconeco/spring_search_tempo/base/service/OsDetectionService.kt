package com.oconeco.spring_search_tempo.base.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.FileStore
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Service for detecting operating system and filesystem type.
 * Provides OS-aware configuration selection for cross-platform support.
 */
@Service
class OsDetectionService {

    private val logger = LoggerFactory.getLogger(OsDetectionService::class.java)

    enum class OsType {
        WINDOWS,
        LINUX,
        MAC,
        UNKNOWN
    }

    enum class FilesystemType {
        NTFS,      // Windows
        FAT32,     // Windows/USB
        EXT4,      // Linux
        EXT3,      // Linux
        XFS,       // Linux
        BTRFS,     // Linux
        APFS,      // macOS
        HFS_PLUS,  // macOS
        UNKNOWN
    }

    /**
     * Detected OS type (cached after first detection)
     */
    val osType: OsType by lazy {
        detectOsType()
    }

    /**
     * Detected filesystem type for the current working directory (cached)
     */
    val currentFilesystemType: FilesystemType by lazy {
        detectFilesystemType(Paths.get("."))
    }

    /**
     * Detect the operating system type from system properties
     */
    private fun detectOsType(): OsType {
        val osName = System.getProperty("os.name", "unknown").lowercase()

        val detected = when {
            osName.contains("win") -> OsType.WINDOWS
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> OsType.LINUX
            osName.contains("mac") || osName.contains("darwin") -> OsType.MAC
            else -> OsType.UNKNOWN
        }

        logger.info("Detected OS: {} (from system property: {})", detected, osName)
        return detected
    }

    /**
     * Detect filesystem type for a given path
     */
    fun detectFilesystemType(path: Path): FilesystemType {
        return try {
            val fileStore: FileStore = Files.getFileStore(path)
            val fsType = fileStore.type().lowercase()

            val detected = when {
                fsType.contains("ntfs") -> FilesystemType.NTFS
                fsType.contains("fat32") || fsType.contains("vfat") -> FilesystemType.FAT32
                fsType.contains("ext4") -> FilesystemType.EXT4
                fsType.contains("ext3") -> FilesystemType.EXT3
                fsType.contains("xfs") -> FilesystemType.XFS
                fsType.contains("btrfs") -> FilesystemType.BTRFS
                fsType.contains("apfs") -> FilesystemType.APFS
                fsType.contains("hfs") -> FilesystemType.HFS_PLUS
                else -> FilesystemType.UNKNOWN
            }

            logger.debug("Filesystem type for path {}: {} (raw: {})", path, detected, fsType)
            detected
        } catch (e: Exception) {
            logger.warn("Failed to detect filesystem type for path {}: {}", path, e.message)
            FilesystemType.UNKNOWN
        }
    }

    /**
     * Get all available filesystem roots (drives on Windows, / on Unix)
     */
    fun getFilesystemRoots(): List<Path> {
        return FileSystems.getDefault().rootDirectories.toList()
    }

    /**
     * Check if the current OS is Windows
     */
    fun isWindows(): Boolean = osType == OsType.WINDOWS

    /**
     * Check if the current OS is Linux
     */
    fun isLinux(): Boolean = osType == OsType.LINUX

    /**
     * Check if the current OS is macOS
     */
    fun isMac(): Boolean = osType == OsType.MAC

    /**
     * Get recommended Spring profile based on OS
     */
    fun getRecommendedProfile(): String {
        return when (osType) {
            OsType.WINDOWS -> "windows"
            OsType.LINUX -> "linux"
            OsType.MAC -> "mac"
            OsType.UNKNOWN -> "default"
        }
    }

    /**
     * Get information about all filesystem roots with their types
     */
    fun getFilesystemInfo(): Map<String, FilesystemType> {
        return getFilesystemRoots().associate { root ->
            root.toString() to detectFilesystemType(root)
        }
    }

    /**
     * Log detailed OS and filesystem information
     */
    fun logSystemInfo() {
        logger.info("=== System Information ===")
        logger.info("Operating System: {}", osType)
        logger.info("OS Name: {}", System.getProperty("os.name"))
        logger.info("OS Version: {}", System.getProperty("os.version"))
        logger.info("OS Arch: {}", System.getProperty("os.arch"))
        logger.info("User Home: {}", System.getProperty("user.home"))
        logger.info("Current Working Directory Filesystem: {}", currentFilesystemType)

        logger.info("=== Filesystem Roots ===")
        getFilesystemInfo().forEach { (root, fsType) ->
            logger.info("  {} -> {}", root, fsType)
        }
    }
}
