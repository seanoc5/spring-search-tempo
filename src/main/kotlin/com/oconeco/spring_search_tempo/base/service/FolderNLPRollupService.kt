package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.model.EntityOccurrence
import com.oconeco.spring_search_tempo.base.model.FolderNLPRollupDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.repos.FSFileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Builds [FolderNLPRollupDTO]s for the folder detail view.
 *
 * Runs two aggregations per request (sentiment + named-entity rollup) against `content_chunks`.
 * Tracking-issue #151 expects on-demand evaluation; if profiling shows a slow path on big
 * folders we can swap in a materialized rollup table without touching the controller.
 */
@Service
class FolderNLPRollupService(
    private val contentChunkRepository: ContentChunkRepository,
    private val fsFileRepository: FSFileRepository,
) {

    companion object {
        /**
         * File-level analysis tiers that produce chunks worth rolling up. INDEX populates text
         * but not NLP fields; ANALYZE/SEMANTIC add sentiment + entities. We include INDEX in the
         * file count so the panel can still render a "0 of N indexed files have NLP" hint, but
         * sentiment/entity aggregations naturally filter to chunks with nlpProcessedAt set.
         */
        val INDEXED_LEVELS: List<AnalysisStatus> =
            listOf(AnalysisStatus.INDEX, AnalysisStatus.ANALYZE, AnalysisStatus.SEMANTIC)

        /** Entity types worth surfacing in the rollup panel, in display order. */
        val DISPLAY_ENTITY_TYPES: List<String> =
            listOf("PERSON", "ORGANIZATION", "LOCATION", "DATE", "MONEY")

        /** Top-N entities per type. */
        const val ENTITIES_PER_TYPE: Int = 10
    }

    @Transactional(readOnly = true)
    fun getRollup(folderId: Long): FolderNLPRollupDTO {
        val indexedFileCount =
            fsFileRepository.countByFsFolderIdAndAnalysisStatusIn(folderId, INDEXED_LEVELS)
        if (indexedFileCount == 0L) {
            return FolderNLPRollupDTO(
                folderId = folderId,
                indexedFileCount = 0L,
                totalChunkCount = 0L,
                sentimentCounts = emptyMap(),
                averageSentimentScore = null,
                lastNlpProcessedAt = null,
                topEntitiesByType = emptyMap(),
            )
        }

        val sentimentRows =
            contentChunkRepository.aggregateSentimentForFolder(folderId, INDEXED_LEVELS)

        val sentimentCounts = mutableMapOf<String, Long>()
        var totalChunks = 0L
        var weightedScoreSum = 0.0
        var weightedScoreCount = 0L
        var lastNlp: OffsetDateTime? = null

        sentimentRows.forEach { row ->
            val sentiment = (row[0] as? String) ?: "UNSPECIFIED"
            val count = (row[1] as Number).toLong()
            val avgScore = (row[2] as? Number)?.toDouble()
            val maxNlp = row[3] as? OffsetDateTime

            sentimentCounts.merge(sentiment, count) { a, b -> a + b }
            totalChunks += count
            if (avgScore != null) {
                weightedScoreSum += avgScore * count
                weightedScoreCount += count
            }
            if (maxNlp != null && (lastNlp == null || maxNlp.isAfter(lastNlp))) {
                lastNlp = maxNlp
            }
        }

        val averageSentimentScore =
            if (weightedScoreCount > 0L) weightedScoreSum / weightedScoreCount else null

        val entityRows = contentChunkRepository.aggregateNamedEntitiesForFolder(folderId)
        val topEntitiesByType: Map<String, List<EntityOccurrence>> = entityRows
            .asSequence()
            .map { row ->
                Triple(
                    row[0] as String,
                    row[1] as String,
                    (row[2] as Number).toLong(),
                )
            }
            .filter { (type, _, _) -> type in DISPLAY_ENTITY_TYPES }
            .groupBy({ it.first }, { EntityOccurrence(text = it.second, count = it.third) })
            .mapValues { (_, occurrences) -> occurrences.take(ENTITIES_PER_TYPE) }

        return FolderNLPRollupDTO(
            folderId = folderId,
            indexedFileCount = indexedFileCount,
            totalChunkCount = totalChunks,
            sentimentCounts = sentimentCounts.toMap(),
            averageSentimentScore = averageSentimentScore,
            lastNlpProcessedAt = lastNlp,
            topEntitiesByType = topEntitiesByType,
        )
    }
}
