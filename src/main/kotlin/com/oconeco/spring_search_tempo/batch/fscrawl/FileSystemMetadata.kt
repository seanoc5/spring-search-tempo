package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.config.MetadataGatherMode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * Lightweight filesystem metadata for efficient staleness comparison.
 * Used to determine if a file/folder has changed since last crawl without
 * expensive operations like text extraction.
 *
 * Bulk gathering: see [FileSystemMetadataGatherer.fromPaths]. The companion
 * [fromPath] entry point is retained for callers (folder processing) that
 * only need a single record; both paths funnel through the same `readOne`
 * implementation and the same Micrometer timer when a registry is bound.
 *
 * @param name File or folder name
 * @param size File size in bytes (0 for directories)
 * @param lastModified Last modification timestamp
 */
data class FileSystemMetadata(
    val name: String,
    val path: Path,
    val size: Long,
    val lastModified: OffsetDateTime?
) {
    companion object {
        private val log = LoggerFactory.getLogger(FileSystemMetadata::class.java)

        /**
         * Extract lightweight metadata from a single filesystem path.
         * For batches use [FileSystemMetadataGatherer.fromPaths] instead — it
         * shares one timer and (in PARALLEL mode) fans out across a bounded
         * ForkJoinPool. See issue #148.
         *
         * @param path Filesystem path to extract metadata from
         * @return FileSystemMetadata or null if path is inaccessible
         */
        fun fromPath(path: Path): FileSystemMetadata? = readOne(path)

        internal fun readOne(path: Path): FileSystemMetadata? {
            return try {
                val name = path.name

                val size = try {
                    if (Files.isRegularFile(path)) {
                        path.fileSize()
                    } else {
                        log.debug("[{}] Path is not a regular file", path)
                        0L
                    }
                } catch (e: Exception) {
                    log.warn("Failed to get size for: {}, using 0", path, e)
                    0L
                }

                val lastModified = try {
                    val fileTime = Files.getLastModifiedTime(path)
                    OffsetDateTime.ofInstant(
                        fileTime.toInstant(),
                        ZoneId.systemDefault()
                    ).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                } catch (e: Exception) {
                    log.warn("Failed to get last modified time for: {}", path, e)
                    null
                }

                FileSystemMetadata(
                    name = name,
                    path = path,
                    size = size,
                    lastModified = lastModified
                )
            } catch (e: Exception) {
                log.warn("Failed to extract metadata from path: {}", path, e)
                null
            }
        }
    }

    /**
     * Check if this metadata indicates the file/folder is unchanged compared to DB record.
     *
     * Comparison strategy:
     * 1. Name must match (if renamed, treat as different entity)
     * 2. Size must match for files (quick check before expensive timestamp comparison)
     * 3. Last modified timestamp must match exactly
     *
     * NOTE: Timestamps are truncated to MILLISECONDS (3 decimal places) for reliable comparison.
     *       While PostgreSQL stores microseconds, JDBC/JPA can introduce rounding differences
     *       at the microsecond level. Millisecond precision is more than sufficient for
     *       detecting file modifications.
     *
     * @param dbLastModified Last modified timestamp from database
     * @param dbSize Size from database (for files)
     * @return true if metadata indicates no changes
     */
    fun isUnchanged(dbLastModified: OffsetDateTime?, dbSize: Long?): Boolean {
        if (lastModified == null) {
            log.warn("Filesystem last modified time is null for path: {}", name)
            return false
        }

        if (dbLastModified == null) {
            log.warn("Database last modified time is null for path: {}", name)
            return false
        }

        if (size > 0 && dbSize != null && size != dbSize) {
            log.info("Size mismatch: fs={}, db={}", size, dbSize)
            return false
        }

        val fsTruncated = lastModified.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val dbTruncated = dbLastModified.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val timestampMatches = fsTruncated.isEqual(dbTruncated)

        if (!timestampMatches) {
            log.info("\t\tTimestamp mismatch for {}: fs={}, db={}", name, fsTruncated, dbTruncated)
        }

        return timestampMatches
    }
}

/**
 * Bulk metadata gatherer for [FileSystemMetadata]. Exists so that
 * [com.oconeco.spring_search_tempo.batch.fscrawl.CombinedCrawlProcessor.processFiles]
 * can stat a directory's worth of files in one call — sequentially, in parallel,
 * or (future) via a batched OS-level syscall — driven by
 * `app.crawl.metadata-gather-mode`. See issue #148.
 *
 * The Micrometer timer published here is `tempo.crawl.metadata.read` with a
 * `mode` tag (`sequential` / `parallel` / `bulk`). Per-call timing means the
 * timer count equals the number of stat operations and the rate gives a
 * per-file latency baseline that's directly comparable across modes.
 *
 * Why bounded parallelism: `Files.walk(...).parallel()` shares the JVM's
 * common ForkJoinPool with the rest of the application (and other batch
 * steps). On a hot crawl that pool fills with stat work and starves
 * unrelated workloads. A dedicated pool with parallelism = min(cpus, 8)
 * gets the benefit without the collateral damage; 8 is the ceiling because
 * the bottleneck is syscall/IO latency, not CPU — adding more threads past
 * that pays diminishing returns and increases lock contention on the
 * filesystem.
 */
class FileSystemMetadataGatherer(
    private val mode: MetadataGatherMode = MetadataGatherMode.SEQUENTIAL,
    meterRegistry: MeterRegistry? = null
) {
    private val timer: Timer? = meterRegistry?.let {
        Timer.builder(METRIC_NAME)
            .description("Time spent reading filesystem stat metadata per path")
            .tag("mode", mode.name.lowercase())
            .register(it)
    }

    private val pool: ForkJoinPool? = when (mode) {
        MetadataGatherMode.PARALLEL, MetadataGatherMode.BULK -> ForkJoinPool(parallelism())
        MetadataGatherMode.SEQUENTIAL -> null
    }

    /**
     * Gather metadata for every path. Element i in the returned list
     * corresponds to `paths[i]`; null entries mean the path was inaccessible
     * (matches the per-call [FileSystemMetadata.fromPath] contract).
     */
    fun fromPaths(paths: List<Path>): List<FileSystemMetadata?> {
        if (paths.isEmpty()) return emptyList()

        return when (mode) {
            MetadataGatherMode.SEQUENTIAL -> paths.map { readTimed(it) }
            MetadataGatherMode.PARALLEL -> readParallel(paths)
            MetadataGatherMode.BULK -> {
                if (bulkWarned.compareAndSet(false, true)) {
                    log.warn(
                        "metadata-gather-mode=BULK requested but OS-batched stat path is not implemented; " +
                                "falling back to PARALLEL. Track issue #148 for the JNA/getdents64 follow-up."
                    )
                }
                readParallel(paths)
            }
        }
    }

    /** Single-path convenience that still records timing under the configured mode tag. */
    fun fromPath(path: Path): FileSystemMetadata? = readTimed(path)

    /** Release the dedicated pool. Idempotent. */
    fun shutdown() {
        pool?.shutdown()
    }

    private fun readParallel(paths: List<Path>): List<FileSystemMetadata?> {
        val executor = pool ?: return paths.map { readTimed(it) }
        val task = executor.submit<List<FileSystemMetadata?>> {
            paths.parallelStream()
                .map { readTimed(it) }
                .toList()
        }
        return task.get()
    }

    private fun readTimed(path: Path): FileSystemMetadata? {
        val t = timer ?: return FileSystemMetadata.readOne(path)
        val start = System.nanoTime()
        try {
            return FileSystemMetadata.readOne(path)
        } finally {
            t.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }

    companion object {
        const val METRIC_NAME: String = "tempo.crawl.metadata.read"
        private val log = LoggerFactory.getLogger(FileSystemMetadataGatherer::class.java)
        private val bulkWarned = AtomicBoolean(false)

        fun parallelism(): Int = minOf(Runtime.getRuntime().availableProcessors(), 8).coerceAtLeast(1)
    }
}
