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
 * Effective patterns for a crawl (folder + file patterns).
 */
data class EffectivePatterns(
    val folderPatterns: PatternSet,
    val filePatterns: PatternSet
)
