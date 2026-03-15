package com.oconeco.spring_search_tempo.base.service

import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for search statistics and analytics.
 *
 * Queries the search_stats materialized view for index coverage statistics.
 */
interface SearchStatsService {

    /**
     * Get search index statistics.
     *
     * @return Statistics about indexed content
     */
    fun getStats(): SearchStats

    /**
     * Refresh the statistics materialized view.
     */
    fun refreshStats()
}

/**
 * Search index statistics.
 */
data class SearchStats(
    /** Total files in the system */
    val totalFiles: Long,
    /** Files with FTS vectors indexed */
    val indexedFiles: Long,
    /** Total text content size in bytes */
    val totalFileTextBytes: Long,
    /** Total content chunks */
    val totalChunks: Long,
    /** Chunks with FTS vectors indexed */
    val indexedChunks: Long,
    /** Total chunk text size in bytes */
    val totalChunkTextBytes: Long,
    /** Chunks with embeddings */
    val chunksWithEmbeddings: Long,
    /** Whether suggestion service is available (pg_trgm) */
    val suggestionsAvailable: Boolean
)

@Service
@Transactional(readOnly = true)
class SearchStatsServiceImpl(
    private val entityManager: EntityManager,
    private val suggestionService: SearchSuggestionService
) : SearchStatsService {

    companion object {
        private val log = LoggerFactory.getLogger(SearchStatsServiceImpl::class.java)
    }

    override fun getStats(): SearchStats {
        return try {
            getStatsFromView()
        } catch (e: Exception) {
            log.warn("Failed to query search_stats view, computing directly: {}", e.message)
            computeStatsDirect()
        }
    }

    private fun getStatsFromView(): SearchStats {
        val sql = """
            SELECT table_name, total_documents, indexed_documents, total_text_bytes
            FROM search_stats
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val results = entityManager.createNativeQuery(sql).resultList as List<Array<Any?>>

        var totalFiles = 0L
        var indexedFiles = 0L
        var fileTextBytes = 0L
        var totalChunks = 0L
        var indexedChunks = 0L
        var chunkTextBytes = 0L

        results.forEach { row ->
            val tableName = row[0] as String
            val total = (row[1] as Number).toLong()
            val indexed = (row[2] as Number).toLong()
            val textBytes = (row[3] as Number?)?.toLong() ?: 0L

            when (tableName) {
                "fsfile" -> {
                    totalFiles = total
                    indexedFiles = indexed
                    fileTextBytes = textBytes
                }
                "content_chunks" -> {
                    totalChunks = total
                    indexedChunks = indexed
                    chunkTextBytes = textBytes
                }
            }
        }

        val chunksWithEmbeddings = countChunksWithEmbeddings()

        return SearchStats(
            totalFiles = totalFiles,
            indexedFiles = indexedFiles,
            totalFileTextBytes = fileTextBytes,
            totalChunks = totalChunks,
            indexedChunks = indexedChunks,
            totalChunkTextBytes = chunkTextBytes,
            chunksWithEmbeddings = chunksWithEmbeddings,
            suggestionsAvailable = suggestionService.isAvailable()
        )
    }

    private fun computeStatsDirect(): SearchStats {
        val fileStats = try {
            val sql = """
                SELECT COUNT(*), COUNT(fts_vector), COALESCE(SUM(LENGTH(body_text)), 0)
                FROM fsfile
            """.trimIndent()
            val result = entityManager.createNativeQuery(sql).singleResult as Array<Any?>
            Triple(
                (result[0] as Number).toLong(),
                (result[1] as Number).toLong(),
                (result[2] as Number).toLong()
            )
        } catch (e: Exception) {
            log.warn("Failed to get file stats: {}", e.message)
            Triple(0L, 0L, 0L)
        }

        val chunkStats = try {
            val sql = """
                SELECT COUNT(*), COUNT(fts_vector), COALESCE(SUM(LENGTH(text)), 0)
                FROM content_chunks
            """.trimIndent()
            val result = entityManager.createNativeQuery(sql).singleResult as Array<Any?>
            Triple(
                (result[0] as Number).toLong(),
                (result[1] as Number).toLong(),
                (result[2] as Number).toLong()
            )
        } catch (e: Exception) {
            log.warn("Failed to get chunk stats: {}", e.message)
            Triple(0L, 0L, 0L)
        }

        val chunksWithEmbeddings = countChunksWithEmbeddings()

        return SearchStats(
            totalFiles = fileStats.first,
            indexedFiles = fileStats.second,
            totalFileTextBytes = fileStats.third,
            totalChunks = chunkStats.first,
            indexedChunks = chunkStats.second,
            totalChunkTextBytes = chunkStats.third,
            chunksWithEmbeddings = chunksWithEmbeddings,
            suggestionsAvailable = suggestionService.isAvailable()
        )
    }

    private fun countChunksWithEmbeddings(): Long {
        return try {
            val sql = "SELECT COUNT(*) FROM content_chunks WHERE embedding IS NOT NULL"
            (entityManager.createNativeQuery(sql).singleResult as Number).toLong()
        } catch (e: Exception) {
            log.warn("Failed to count chunks with embeddings: {}", e.message)
            0L
        }
    }

    @Transactional
    override fun refreshStats() {
        try {
            entityManager.createNativeQuery("SELECT refresh_search_stats()").singleResult
            log.info("Search stats materialized view refreshed")
        } catch (e: Exception) {
            log.warn("Failed to refresh search stats: {}", e.message)
        }
    }
}
