package com.oconeco.remotecrawler.discovery

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Disk-backed cache for onboarding discovery results.
 *
 * Saves large folder lists before upload so a failed upload can be retried
 * without walking the filesystem again.
 */
class DiscoveryCache(
    private val host: String,
    rootPaths: List<Path>,
    private val maxDepth: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val normalizedRoots = rootPaths
        .map { it.toAbsolutePath().normalize().toString() }
        .sorted()

    private val cacheFile: Path = cacheDir()
        .resolve("${sanitize(host)}-${cacheKey()}.json.gz")

    fun loadIfFresh(maxAge: Duration = Duration.ofDays(2)): CachedDiscoveryEntry? {
        if (!Files.exists(cacheFile)) {
            return null
        }

        return try {
            Files.newInputStream(cacheFile).use { raw ->
                GZIPInputStream(BufferedInputStream(raw)).use { gz ->
                    val payload = objectMapper.readValue(gz, CachedDiscoveryPayload::class.java)
                    val age = Duration.between(payload.createdAt, Instant.now())
                    if (age.isNegative || age <= maxAge) {
                        CachedDiscoveryEntry(
                            cacheFile = cacheFile,
                            payload = payload,
                            age = age
                        )
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to read discovery cache {}", cacheFile, e)
            null
        }
    }

    fun save(payload: CachedDiscoveryPayload): Path {
        Files.createDirectories(cacheFile.parent)
        Files.newOutputStream(cacheFile).use { raw ->
            GZIPOutputStream(BufferedOutputStream(raw)).use { gz ->
                objectMapper.writeValue(gz, payload)
            }
        }
        return cacheFile
    }

    fun cachePath(): Path = cacheFile

    private fun cacheKey(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(host.toByteArray())
        digest.update(0.toByte())
        normalizedRoots.forEach { root ->
            digest.update(root.toByteArray())
            digest.update(0.toByte())
        }
        digest.update(maxDepth.toString().toByteArray())
        return HexFormat.of().formatHex(digest.digest()).take(12)
    }

    private fun sanitize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-")

    private fun cacheDir(): Path {
        val home = System.getProperty("user.home")?.trim().orEmpty()
        return if (home.isBlank()) {
            Paths.get(".tempo", "remote-crawler", "discovery-cache")
        } else {
            Paths.get(home, ".tempo", "remote-crawler", "discovery-cache")
        }
    }

    companion object {
        private val objectMapper: ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

data class CachedDiscoveryEntry(
    val cacheFile: Path,
    val payload: CachedDiscoveryPayload,
    val age: Duration
)

data class CachedDiscoveryPayload(
    val host: String,
    val osType: String,
    val rootPaths: List<String>,
    val maxDepth: Int,
    val createdAt: Instant,
    val discoveryDurationMs: Long,
    val totalFolders: Int,
    val skippedFolders: Int,
    val errorCount: Int,
    val folders: List<DiscoveredFolder>
) {
    fun toDiscoveryResult(): DiscoveryResult =
        DiscoveryResult(
            folders = folders,
            totalFolders = totalFolders,
            skippedFolders = skippedFolders,
            errorCount = errorCount,
            durationMs = discoveryDurationMs
        )
}
