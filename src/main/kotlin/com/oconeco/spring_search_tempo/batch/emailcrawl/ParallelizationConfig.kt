package com.oconeco.spring_search_tempo.batch.emailcrawl

/**
 * Configuration for email body enrichment parallelization.
 *
 * Supports four modes:
 * 1. Serial+prefetch (default): stepThreads=1, itemAsync=false — single-
 *    threaded step that bulk-fetches ~`chunkSize` bodies per IMAP round-trip
 *    via `FetchProfile` (see `PrefetchingBodyEnrichmentReader`). This is the
 *    new default introduced for issue #58 and benchmarks roughly an order of
 *    magnitude faster than the old per-message FETCH against high-latency
 *    IMAP hosts like Gmail.
 * 2. TaskExecutor only: stepThreads>1, itemAsync=false — parallel chunks
 * 3. AsyncItemProcessor only: stepThreads=1, itemAsync=true — async items
 * 4. Combined: stepThreads>1, itemAsync=true — both strategies
 *
 * @param stepThreads Number of threads for step-level parallelism (chunk processing)
 * @param itemAsync Whether to use AsyncItemProcessor for item-level parallelism
 * @param asyncThreads Number of threads for AsyncItemProcessor (only if itemAsync=true)
 * @param chunkSize Number of items per chunk; doubles as the FetchProfile
 *   prefetch batch size in serial mode.
 */
data class ParallelizationConfig(
    val stepThreads: Int = 1,
    val itemAsync: Boolean = false,
    val asyncThreads: Int = 4,
    val chunkSize: Int = 50  // PERFORMANCE: Increased for bulk load (was 20)
) {
    /**
     * True if any parallelization is enabled.
     */
    val isParallel: Boolean get() = stepThreads > 1 || itemAsync

    /**
     * Descriptive mode name for logging.
     */
    val modeName: String get() = when {
        stepThreads > 1 && itemAsync -> "combined(step=$stepThreads,async=$asyncThreads)"
        stepThreads > 1 -> "taskExecutor($stepThreads)"
        itemAsync -> "asyncItem($asyncThreads)"
        else -> "serial+prefetch($chunkSize)"
    }

    override fun toString(): String =
        "ParallelizationConfig(mode=$modeName, chunkSize=$chunkSize)"
}
