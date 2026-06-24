package com.oconeco.spring_search_tempo.base.model

import java.time.OffsetDateTime

/**
 * Aggregate NLP signals across all INDEX/ANALYZE/SEMANTIC files directly owned by a single folder.
 *
 * Computed on-demand (see [com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository.aggregateSentimentForFolder]
 * and `aggregateNamedEntitiesForFolder`). If the on-demand cost grows on folders with thousands
 * of indexed files, the natural next step is a denormalized rollup table refreshed by the NLP
 * job listener — but issue #151 calls for measuring first; see the PR body.
 *
 * `indexedFileCount == 0` means no INDEX/ANALYZE/SEMANTIC files live directly in this folder;
 * the UI should not render the panel in that case.
 */
data class FolderNLPRollupDTO(
    val folderId: Long,
    val indexedFileCount: Long,
    val totalChunkCount: Long,
    val sentimentCounts: Map<String, Long>,
    val averageSentimentScore: Double?,
    val lastNlpProcessedAt: OffsetDateTime?,
    val topEntitiesByType: Map<String, List<EntityOccurrence>>,
) {
    fun isEmpty(): Boolean = indexedFileCount == 0L

    fun hasNlpData(): Boolean = totalChunkCount > 0L

    val positiveCount: Long get() = sentimentCounts["POSITIVE"] ?: 0L
    val neutralCount: Long get() = sentimentCounts["NEUTRAL"] ?: 0L
    val negativeCount: Long get() = sentimentCounts["NEGATIVE"] ?: 0L
    val unspecifiedCount: Long get() = sentimentCounts["UNSPECIFIED"] ?: 0L

    val positivePercent: Double get() = pct(positiveCount)
    val neutralPercent: Double get() = pct(neutralCount)
    val negativePercent: Double get() = pct(negativeCount)
    val unspecifiedPercent: Double get() = pct(unspecifiedCount)

    private fun pct(n: Long): Double =
        if (totalChunkCount > 0L) (n * 100.0) / totalChunkCount else 0.0

    /**
     * Entity sections in display order, filtered to those with at least one entry.
     * Lets the template iterate without computing per-type emptiness inline.
     */
    val displayEntitySections: List<EntitySection>
        get() = DISPLAY_ORDER.mapNotNull { type ->
            val entries = topEntitiesByType[type]
            if (entries.isNullOrEmpty()) null else EntitySection(type, entries)
        }

    companion object {
        val DISPLAY_ORDER: List<String> =
            listOf("PERSON", "ORGANIZATION", "LOCATION", "DATE", "MONEY")
    }
}

data class EntitySection(
    val type: String,
    val entries: List<EntityOccurrence>,
)

data class EntityOccurrence(
    val text: String,
    val count: Long,
)
