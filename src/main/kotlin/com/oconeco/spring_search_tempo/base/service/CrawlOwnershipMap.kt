package com.oconeco.spring_search_tempo.base.service

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

/**
 * Structural ownership map for cross-crawl subtree routing.
 *
 * Built from [CrawlConfigService.getEnabledCrawls], maps a canonicalized
 * start-path to the name of the crawl that owns it. When a different crawl
 * encounters that path during a walk, it can short-circuit to skip-subtree
 * without consulting the database — ownership is declared structurally by
 * configuration, independent of any recency signal.
 *
 * Coexists with [RecentCrawlSkipChecker]'s recency query as the primary
 * (structural) layer; recency remains the fallback for yaml-only / ad-hoc
 * crawl variants that aren't represented in [CrawlConfigService].
 *
 * Path canonicalization uses [Path.toAbsolutePath] followed by
 * [Path.normalize] at build time. Symlinks are intentionally not resolved
 * — this matches the file-system visitor's behavior (which walks paths as
 * configured, not as `realpath`-resolved).
 */
@Service
class CrawlOwnershipMap(
    private val crawlConfigService: CrawlConfigService
) {
    companion object {
        private val log = LoggerFactory.getLogger(CrawlOwnershipMap::class.java)
    }

    private val pathToCrawl: AtomicReference<Map<Path, String>> = AtomicReference(emptyMap())

    @PostConstruct
    fun init() {
        refresh()
    }

    /**
     * Rebuild the map from current crawl configuration. Safe to invoke at
     * runtime; replaces the current map atomically.
     *
     * Logs a WARN line per start-path that would collide with one already
     * claimed by another crawl — operator misconfiguration that should be
     * surfaced, not silently flattened.
     */
    fun refresh() {
        val crawls = crawlConfigService.getEnabledCrawls()
        val built = mutableMapOf<Path, String>()

        for (crawl in crawls) {
            for (raw in crawl.startPaths) {
                val canonical = canonicalize(raw) ?: continue
                val existing = built[canonical]
                if (existing != null && existing != crawl.name) {
                    log.warn(
                        "Crawl ownership collision at {}: claimed by both '{}' and '{}'. " +
                            "First-claim wins ('{}'); fix the configuration to remove the duplicate.",
                        canonical, existing, crawl.name, existing
                    )
                    continue
                }
                built[canonical] = crawl.name
            }
        }

        pathToCrawl.set(built.toMap())
        log.info("CrawlOwnershipMap built: {} owned paths across {} enabled crawls",
            built.size, crawls.size)
    }

    /**
     * Return the name of the crawl that owns [path], or null if no crawl
     * claims it. The lookup canonicalizes [path] the same way the map keys
     * were built so callers can pass the visitor's raw path.
     */
    fun lookup(path: Path): String? {
        val canonical = canonicalize(path) ?: return null
        return pathToCrawl.get()[canonical]
    }

    /**
     * Snapshot of the current ownership map. Diagnostic / admin use only.
     */
    fun ownedPaths(): Map<Path, String> = pathToCrawl.get()

    private fun canonicalize(raw: String): Path? {
        return try {
            canonicalize(Paths.get(raw))
        } catch (e: InvalidPathException) {
            log.warn("Skipping invalid start-path '{}' while building ownership map: {}",
                raw, e.message)
            null
        }
    }

    private fun canonicalize(raw: Path): Path? {
        return try {
            raw.toAbsolutePath().normalize()
        } catch (e: Exception) {
            log.warn("Failed to canonicalize path '{}' for ownership lookup: {}",
                raw, e.message)
            null
        }
    }
}
