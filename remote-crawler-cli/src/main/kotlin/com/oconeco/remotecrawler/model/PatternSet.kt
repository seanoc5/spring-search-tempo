package com.oconeco.remotecrawler.model

/**
 * Pattern set for determining AnalysisStatus at different levels.
 * Each list contains regex patterns to match against file/folder paths.
 */
data class PatternSet(
    val skip: List<String> = emptyList(),
    val locate: List<String> = emptyList(),
    val index: List<String> = emptyList(),
    val analyze: List<String> = emptyList(),
    val semantic: List<String> = emptyList()
)

/**
 * Explicit priority ordering for pattern categories.
 * Higher number means higher precedence.
 */
data class PatternPriority(
    val skip: Int = 500,
    val semantic: Int = 400,
    val analyze: Int = 300,
    val index: Int = 200,
    val locate: Int = 100
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
 * Effective patterns for a crawl (folder + file patterns).
 */
data class EffectivePatterns(
    val folderPatterns: PatternSet,
    val filePatterns: PatternSet,
    val folderPatternPriority: PatternPriority = PatternPriority(),
    val filePatternPriority: PatternPriority = PatternPriority()
)
