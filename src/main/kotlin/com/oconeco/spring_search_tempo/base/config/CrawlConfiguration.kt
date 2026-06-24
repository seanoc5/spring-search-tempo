package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Configuration properties for file system crawling.
 * Supports multiple crawl definitions with global defaults.
 */
@Configuration
@ConfigurationProperties(prefix = "app.crawl")
data class CrawlConfiguration(
    var defaults: CrawlDefaults = CrawlDefaults(),
    var crawls: List<CrawlDefinition> = emptyList(),
    /**
     * App-wide absolute ceiling on directory-walk depth (issue #105).
     *
     * Per-config [CrawlDefinition.maxDepth] is the day-to-day knob and
     * continues to win when it is smaller. This is a cross-cutting backstop
     * that prevents any walker — `FilesystemFolderAuditJob`, `DiscoveryReader`,
     * `CombinedCrawlReader` — from descending past a sane depth even when
     * the per-config value is misconfigured (or absent on the audit, which
     * has no per-config knob of its own). 50 levels comfortably exceeds any
     * legitimate filesystem depth.
     */
    var absoluteMaxDepth: Int = 50,
    /**
     * How [com.oconeco.spring_search_tempo.batch.fscrawl.FileSystemMetadata] gathers
     * per-file stat data during a crawl batch (issue #148).
     *
     * - SEQUENTIAL — one `Files.readAttributes` per path on the calling thread.
     *   Lowest overhead; the right default for SSDs where syscall latency is sub-millisecond.
     * - PARALLEL — bounded ForkJoinPool fan-out (parallelism = min(cpus, 8)).
     *   Pays off on spinning disks and network mounts where stat latency dominates wall-clock.
     * - BULK — placeholder for an OS-specific batched-syscall path (Linux `getdents64`
     *   + lazy `statx`). Not yet implemented; falls back to PARALLEL with a one-time warn.
     *
     * Operator can flip this without a code change to measure the win on their own tree.
     */
    var metadataGatherMode: MetadataGatherMode = MetadataGatherMode.SEQUENTIAL,
    /**
     * After chunking finishes for a file, `body_text` is truncated to this
     * many **characters** and a marker is appended (see ADR-006 / issue
     * #147). Character-count is the natural unit here: `body_text` is a
     * PostgreSQL `text` column, `LENGTH()` reports characters, and the
     * existing `fts_vector` substring cap (`substring(body_text, 1, 250000)`)
     * is also character-based. For ASCII text 1,048,576 chars ≈ 1 MB on
     * disk; mostly-UTF-8 content can be 2–4× larger byte-wise — set this
     * lower if you need a tighter on-disk ceiling.
     *
     * Default 1,048,576. Set <= 0 to disable truncation entirely.
     */
    var largeBodyThresholdChars: Long = 1_048_576L
) {
    /**
     * Merge crawl-specific patterns with global defaults.
     *
     * SKIP patterns are merged (defaults + crawl-specific).
     * Items matching SKIP are persisted with metadata only, no further processing.
     * For folders: children are not crawled (processing stops).
     * For files: no text extraction occurs.
     *
     * Other patterns (LOCATE, INDEX, ANALYZE, SEMANTIC) use crawl-specific only.
     */
    fun getEffectivePatterns(crawl: CrawlDefinition): EffectivePatterns {
        return EffectivePatterns(
            folderPatterns = PatternSet(
                skip = defaults.folderPatterns.skip + crawl.folderPatterns.skip,
                locate = crawl.folderPatterns.locate,
                index = crawl.folderPatterns.index,
                analyze = crawl.folderPatterns.analyze,
                semantic = crawl.folderPatterns.semantic
            ),
            filePatterns = PatternSet(
                skip = defaults.filePatterns.skip + crawl.filePatterns.skip,
                locate = crawl.filePatterns.locate,
                index = crawl.filePatterns.index,
                analyze = crawl.filePatterns.analyze,
                semantic = crawl.filePatterns.semantic
            ),
            folderPatternPriority = crawl.folderPatternPriority ?: defaults.folderPatternPriority,
            filePatternPriority = crawl.filePatternPriority ?: defaults.filePatternPriority
        )
    }
}

/**
 * Global default settings that all crawls inherit.
 */
data class CrawlDefaults(
    var maxDepth: Int = 10,
    var followLinks: Boolean = false,
    var parallel: Boolean = false,
    var folderPatterns: PatternSet = PatternSet(),
    var filePatterns: PatternSet = PatternSet(),
    var folderPatternPriority: PatternPriority = PatternPriority(),
    var filePatternPriority: PatternPriority = PatternPriority(),
    /**
     * Default hours threshold for "recent crawl" skip logic.
     * If a folder was crawled by another config within this many hours,
     * the current crawl can skip that subtree.
     */
    var recentCrawlSkipHours: Int = 24
)

/**
 * Individual crawl definition with optional overrides of defaults.
 * Supports multiple start paths for crawling multiple directory trees
 * with a single shared configuration.
 */
data class CrawlDefinition(
    var name: String = "",
    var label: String = "",
    var enabled: Boolean = true,
    var startPaths: List<String> = emptyList(),  // Multiple start paths supported
    var maxDepth: Int? = null,  // null = use default
    var followLinks: Boolean? = null,  // null = use default
    var parallel: Boolean? = null,  // null = use default
    var folderPatterns: PatternSet = PatternSet(),
    var filePatterns: PatternSet = PatternSet(),
    var folderPatternPriority: PatternPriority? = null,
    var filePatternPriority: PatternPriority? = null
) {
    /**
     * Get effective maxDepth, using default if not specified.
     */
    fun getMaxDepth(defaults: CrawlDefaults): Int = maxDepth ?: defaults.maxDepth

    /**
     * Get effective followLinks, using default if not specified.
     */
    fun getFollowLinks(defaults: CrawlDefaults): Boolean = followLinks ?: defaults.followLinks

    /**
     * Get effective parallel setting, using default if not specified.
     */
    fun getParallel(defaults: CrawlDefaults): Boolean = parallel ?: defaults.parallel
}

/**
 * Pattern set for determining AnalysisStatus at different levels.
 * Each list contains regex patterns to match against file/folder paths.
 */
data class PatternSet(
    var skip: List<String> = emptyList(),     // SKIP - persist metadata only, no further processing
    var locate: List<String> = emptyList(),   // LOCATE - metadata only (like plocate)
    var index: List<String> = emptyList(),    // INDEX - extract and index text
    var analyze: List<String> = emptyList(),  // ANALYZE - full NLP processing
    var semantic: List<String> = emptyList()  // SEMANTIC - ANALYZE + vector embedding
)

/**
 * Explicit priority ordering for pattern categories.
 * Higher number means higher precedence.
 */
data class PatternPriority(
    var skip: Int = 500,
    var semantic: Int = 400,
    var analyze: Int = 300,
    var index: Int = 200,
    var locate: Int = 100
) {
    fun orderedStatuses(): List<AnalysisStatus> {
        val defaultOrder = listOf(
            AnalysisStatus.SKIP,
            AnalysisStatus.SEMANTIC,
            AnalysisStatus.ANALYZE,
            AnalysisStatus.INDEX,
            AnalysisStatus.LOCATE
        )
        val rank = defaultOrder.withIndex().associate { it.value to it.index }
        return defaultOrder
            .map { status -> status to priorityOf(status) }
            .sortedWith(
                compareByDescending<Pair<AnalysisStatus, Int>> { it.second }
                    .thenBy { rank[it.first] ?: Int.MAX_VALUE }
            )
            .map { it.first }
    }

    fun priorityOf(status: AnalysisStatus): Int = when (status) {
        AnalysisStatus.SKIP -> skip
        AnalysisStatus.SEMANTIC -> semantic
        AnalysisStatus.ANALYZE -> analyze
        AnalysisStatus.INDEX -> index
        AnalysisStatus.LOCATE -> locate
    }
}

/**
 * Strategy for batching filesystem stat calls. See [CrawlConfiguration.metadataGatherMode].
 */
enum class MetadataGatherMode {
    SEQUENTIAL,
    PARALLEL,
    BULK
}

/**
 * Effective patterns after merging defaults with crawl-specific patterns.
 */
data class EffectivePatterns(
    val folderPatterns: PatternSet,
    val filePatterns: PatternSet,
    val folderPatternPriority: PatternPriority = PatternPriority(),
    val filePatternPriority: PatternPriority = PatternPriority()
)
