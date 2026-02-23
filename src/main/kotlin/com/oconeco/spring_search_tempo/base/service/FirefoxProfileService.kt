package com.oconeco.spring_search_tempo.base.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name


/**
 * Service for discovering Firefox profiles on the local system.
 *
 * Firefox stores profiles in platform-specific locations:
 * - Linux: ~/.mozilla/firefox/
 * - macOS: ~/Library/Application Support/Firefox/Profiles/
 * - Windows: %APPDATA%\Mozilla\Firefox\Profiles\
 */
@Service
class FirefoxProfileService {

    companion object {
        private val log = LoggerFactory.getLogger(FirefoxProfileService::class.java)
    }

    /**
     * Discovered Firefox profile with its paths.
     */
    data class DiscoveredProfile(
        val profileName: String,
        val profilePath: Path,
        val placesDbPath: Path
    )

    /**
     * Get the default Firefox profiles directory for the current platform.
     */
    fun getDefaultProfilesPath(): Path? {
        val userHome = System.getProperty("user.home")
        val osName = System.getProperty("os.name").lowercase()

        return when {
            osName.contains("linux") -> Path.of(userHome, ".mozilla", "firefox")
            osName.contains("mac") -> Path.of(userHome, "Library", "Application Support", "Firefox", "Profiles")
            osName.contains("windows") -> {
                val appData = System.getenv("APPDATA") ?: return null
                Path.of(appData, "Mozilla", "Firefox", "Profiles")
            }
            else -> {
                log.warn("Unsupported operating system: {}", osName)
                null
            }
        }
    }

    /**
     * Discover all Firefox profiles with places.sqlite databases.
     *
     * @return List of discovered profiles with their paths
     */
    fun discoverProfiles(): List<DiscoveredProfile> {
        val profilesPath = getDefaultProfilesPath()
        if (profilesPath == null || !profilesPath.exists()) {
            log.warn("Firefox profiles directory not found: {}", profilesPath)
            return emptyList()
        }

        log.info("Scanning for Firefox profiles in: {}", profilesPath)

        return try {
            Files.list(profilesPath).use { stream ->
                stream.filter { it.isDirectory() }
                    .map { profileDir ->
                        val placesDb = profileDir.resolve("places.sqlite")
                        if (placesDb.isRegularFile()) {
                            DiscoveredProfile(
                                profileName = profileDir.name,
                                profilePath = profileDir,
                                placesDbPath = placesDb
                            )
                        } else {
                            null
                        }
                    }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
            }.also { profiles ->
                log.info("Found {} Firefox profiles with places.sqlite", profiles.size)
                profiles.forEach { profile ->
                    log.debug("  Profile: {} at {}", profile.profileName, profile.profilePath)
                }
            }
        } catch (e: Exception) {
            log.error("Error scanning Firefox profiles directory", e)
            emptyList()
        }
    }

    /**
     * Discover profiles in a specific directory (for testing or custom locations).
     */
    fun discoverProfilesIn(profilesPath: Path): List<DiscoveredProfile> {
        if (!profilesPath.exists() || !profilesPath.isDirectory()) {
            log.warn("Profiles directory does not exist: {}", profilesPath)
            return emptyList()
        }

        return try {
            Files.list(profilesPath).use { stream ->
                stream.filter { it.isDirectory() }
                    .map { profileDir ->
                        val placesDb = profileDir.resolve("places.sqlite")
                        if (placesDb.isRegularFile()) {
                            DiscoveredProfile(
                                profileName = profileDir.name,
                                profilePath = profileDir,
                                placesDbPath = placesDb
                            )
                        } else {
                            null
                        }
                    }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
            }
        } catch (e: Exception) {
            log.error("Error scanning profiles directory: {}", profilesPath, e)
            emptyList()
        }
    }

}
