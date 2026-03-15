package com.oconeco.remotecrawler.discovery

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Fast folder-only filesystem discovery.
 *
 * This is the first phase of the onboarding flow:
 * 1. Walk filesystem collecting only folder metadata (no files, no content)
 * 2. Apply hard-coded system exclusions (/proc, /sys, etc.)
 * 3. Collect path, name, depth, estimated child count
 * 4. Send to server for classification UI
 *
 * Designed to be FAST - a full system discovery should complete in seconds,
 * not minutes/hours like a full content crawl.
 */
class FolderDiscovery(
    private val maxDepth: Int = 15,
    private val progressCallback: ((DiscoveryProgress) -> Unit)? = null
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Discover all folders under the given roots.
     */
    fun discover(roots: List<Path>): DiscoveryResult {
        val startTime = System.currentTimeMillis()
        val folders = mutableListOf<DiscoveredFolder>()
        val stats = DiscoveryStats()

        for (root in roots) {
            if (!Files.exists(root)) {
                log.warn("Root path does not exist: {}", root)
                stats.errors.incrementAndGet()
                continue
            }

            if (!Files.isReadable(root)) {
                log.warn("Root path is not readable: {}", root)
                stats.errors.incrementAndGet()
                continue
            }

            log.info("Discovering folders in: {}", root)
            discoverRoot(root, folders, stats)
        }

        val duration = System.currentTimeMillis() - startTime

        log.info(
            "Discovery complete: {} folders in {}ms ({} errors, {} skipped)",
            folders.size, duration, stats.errors.get(), stats.skipped.get()
        )

        return DiscoveryResult(
            folders = folders,
            totalFolders = stats.discovered.get(),
            skippedFolders = stats.skipped.get(),
            errorCount = stats.errors.get(),
            durationMs = duration
        )
    }

    private fun discoverRoot(root: Path, folders: MutableList<DiscoveredFolder>, stats: DiscoveryStats) {
        val rootDepth = root.nameCount
        val recordedPaths = linkedSetOf<String>()

        Files.walkFileTree(root, setOf(), maxDepth, object : SimpleFileVisitor<Path>() {

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val pathStr = dir.toAbsolutePath().toString()
                val specialPolicy = specialFolderPolicy(pathStr)

                if (specialPolicy != null) {
                    recordFolder(
                        dir = dir,
                        pathStr = pathStr,
                        rootDepth = rootDepth,
                        folders = folders,
                        stats = stats,
                        recordedPaths = recordedPaths,
                        forcedSuggestedStatus = specialPolicy.suggestedStatus,
                        skipChildEstimate = !specialPolicy.descend
                    )
                    if (!specialPolicy.descend) {
                        stats.skipped.incrementAndGet()
                        return FileVisitResult.SKIP_SUBTREE
                    }
                }

                // Check system exclusions (hard-coded for performance)
                if (shouldExcludeSystem(pathStr)) {
                    stats.skipped.incrementAndGet()
                    return FileVisitResult.SKIP_SUBTREE
                }

                recordFolder(
                    dir = dir,
                    pathStr = pathStr,
                    rootDepth = rootDepth,
                    folders = folders,
                    stats = stats,
                    recordedPaths = recordedPaths
                )

                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Skip files entirely - we only care about folder structure
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                // Don't log every failure - too noisy
                stats.errors.incrementAndGet()
                if (runCatching { Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)) {
                    val pathStr = file.toAbsolutePath().toString()
                    if (!shouldExcludeSystem(pathStr)) {
                        recordFolder(
                            dir = file,
                            pathStr = pathStr,
                            rootDepth = rootDepth,
                            folders = folders,
                            stats = stats,
                            recordedPaths = recordedPaths,
                            forcedSuggestedStatus = specialFolderPolicy(pathStr)?.suggestedStatus,
                            skipChildEstimate = true
                        )
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    stats.errors.incrementAndGet()
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Estimate immediate children without full enumeration.
     */
    private fun estimateChildren(dir: Path): ChildStats {
        var folders = 0
        var files = 0
        var totalSize = 0L

        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (entry in stream) {
                    if (Files.isDirectory(entry)) {
                        folders++
                    } else {
                        files++
                        try {
                            totalSize += Files.size(entry)
                        } catch (e: Exception) {
                            // Ignore size errors
                        }
                    }
                    // Limit to avoid slow directories
                    if (folders + files > 10000) break
                }
            }
        } catch (e: Exception) {
            // Access denied or other error
        }

        return ChildStats(folders, files, totalSize)
    }

    private data class ChildStats(val folders: Int, val files: Int, val totalSize: Long)

    private fun recordFolder(
        dir: Path,
        pathStr: String,
        rootDepth: Int,
        folders: MutableList<DiscoveredFolder>,
        stats: DiscoveryStats,
        recordedPaths: MutableSet<String>,
        forcedSuggestedStatus: SuggestedStatus? = null,
        skipChildEstimate: Boolean = false
    ) {
        if (!recordedPaths.add(pathStr)) {
            return
        }

        val depth = dir.nameCount - rootDepth
        val childStats = if (skipChildEstimate) ChildStats(0, 0, 0L) else estimateChildren(dir)
        val name = dir.fileName?.toString() ?: pathStr

        folders.add(
            DiscoveredFolder(
                path = pathStr,
                name = name,
                depth = depth,
                folderCount = childStats.folders,
                fileCount = childStats.files,
                totalSize = childStats.totalSize,
                isHidden = isHidden(dir),
                suggestedStatus = forcedSuggestedStatus ?: suggestStatus(pathStr, name)
            )
        )

        stats.discovered.incrementAndGet()
        if (stats.discovered.get() % 1000 == 0) {
            progressCallback?.invoke(
                DiscoveryProgress(
                    foldersDiscovered = stats.discovered.get(),
                    currentPath = pathStr
                )
            )
        }
    }

    /**
     * Check if path should be excluded (system directories, virtual filesystems).
     */
    private fun shouldExcludeSystem(path: String): Boolean {
        val lowerPath = normalizeComparablePath(path)

        // Linux/Unix system directories
        if (lowerPath.startsWith("/proc/") ||
            lowerPath.startsWith("/sys/") ||
            lowerPath.startsWith("/dev/") ||
            lowerPath.startsWith("/run/") ||
            lowerPath.startsWith("/lost+found/")
        ) {
            return true
        }

        // Windows system directories
        if (lowerPath.contains("/windows/") ||
            lowerPath.contains("/\$recycle.bin") ||
            lowerPath.contains("/system volume information") ||
            lowerPath.endsWith("/windows")
        ) {
            return true
        }

        // macOS system directories
        if (lowerPath.startsWith("/system") ||
            lowerPath.startsWith("/private/var") ||
            lowerPath.startsWith("/library/caches") ||
            lowerPath.contains("/library/developer/xcode")
        ) {
            return true
        }

        return false
    }

    internal fun specialFolderPolicy(path: String): SpecialFolderPolicy? {
        return when (normalizeComparablePath(path)) {
            "/proc",
            "/sys",
            "/dev",
            "/run",
            "/lost+found" -> SpecialFolderPolicy(
                descend = false,
                suggestedStatus = SuggestedStatus.SKIP
            )
            else -> null
        }
    }

    private fun normalizeComparablePath(path: String): String {
        var normalized = path.trim().replace('\\', '/').lowercase()
        if (normalized.isBlank()) return "/"
        normalized = normalized.replace(Regex("/{2,}"), "/")
        if (!normalized.startsWith("/")) {
            normalized = "/$normalized"
        }
        if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized = normalized.removeSuffix("/")
        }
        return normalized
    }

    /**
     * Suggest an initial status based on folder name patterns.
     * This is just a hint - user can override in classification UI.
     */
    private fun suggestStatus(path: String, name: String?): SuggestedStatus {
        val lowerPath = path.lowercase()
        val lowerName = name?.lowercase() ?: ""

        // Strong SKIP candidates
        if (lowerName == "node_modules" ||
            lowerName == ".git" ||
            lowerName == "__pycache__" ||
            lowerName == ".gradle" ||
            lowerName == ".m2" ||
            lowerName == ".npm" ||
            lowerName == ".cache" ||
            lowerName == "cache" ||
            lowerName == "caches" ||
            lowerName.startsWith(".")  // Hidden directories
        ) {
            return SuggestedStatus.SKIP
        }

        // Strong INDEX candidates (user content)
        if (lowerName == "documents" ||
            lowerName == "desktop" ||
            lowerName == "downloads" ||
            lowerPath.contains("/src/") ||
            lowerPath.contains("\\src\\")
        ) {
            return SuggestedStatus.INDEX
        }

        // Media folders - LOCATE (metadata only)
        if (lowerName == "pictures" ||
            lowerName == "photos" ||
            lowerName == "music" ||
            lowerName == "videos" ||
            lowerName == "movies"
        ) {
            return SuggestedStatus.LOCATE
        }

        // Default: let user decide
        return SuggestedStatus.UNKNOWN
    }

    private fun isHidden(path: Path): Boolean {
        return try {
            Files.isHidden(path) || path.fileName?.toString()?.startsWith(".") == true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * A discovered folder with metadata.
 */
data class DiscoveredFolder(
    val path: String,
    val name: String,
    val depth: Int,
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val totalSize: Long = 0,
    val isHidden: Boolean = false,
    val suggestedStatus: SuggestedStatus = SuggestedStatus.UNKNOWN
)

/**
 * Suggested analysis status based on folder patterns.
 */
enum class SuggestedStatus {
    SKIP,       // Strong candidate for skipping (caches, build dirs)
    LOCATE,     // Metadata only (media, binaries)
    INDEX,      // Full text extraction (documents, code)
    UNKNOWN     // Let user decide
}

/**
 * Result of folder discovery.
 */
data class DiscoveryResult(
    val folders: List<DiscoveredFolder>,
    val totalFolders: Int,
    val skippedFolders: Int,
    val errorCount: Int,
    val durationMs: Long
)

/**
 * Progress callback data.
 */
data class DiscoveryProgress(
    val foldersDiscovered: Int,
    val currentPath: String
)

/**
 * Internal stats tracking.
 */
private class DiscoveryStats {
    val discovered = AtomicInteger(0)
    val skipped = AtomicInteger(0)
    val errors = AtomicInteger(0)
}

internal data class SpecialFolderPolicy(
    val descend: Boolean,
    val suggestedStatus: SuggestedStatus
)
