package com.oconeco.spring_search_tempo.base.service

import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for search suggestions and autocomplete using PostgreSQL pg_trgm.
 *
 * Uses trigram similarity matching to suggest terms similar to partial user input.
 * Indexes titles, labels, authors, and keywords for fast suggestion lookups.
 */
interface SearchSuggestionService {

    /**
     * Get search suggestions for a partial query.
     *
     * @param partialQuery The partial search term entered by the user
     * @param limit Maximum number of suggestions to return
     * @return List of suggestions ordered by similarity
     */
    fun suggest(partialQuery: String, limit: Int = 10): List<SearchSuggestion>

    /**
     * Check if the suggestion service is available (pg_trgm extension installed).
     */
    fun isAvailable(): Boolean
}

/**
 * A search suggestion with similarity score.
 */
data class SearchSuggestion(
    /** The suggested term */
    val term: String,
    /** Source of the suggestion: title, label, author, keywords */
    val source: String,
    /** Trigram similarity score (0.0 to 1.0) */
    val similarity: Float
)

@Service
@Transactional(readOnly = true)
class SearchSuggestionServiceImpl(
    private val entityManager: EntityManager
) : SearchSuggestionService {

    companion object {
        private val log = LoggerFactory.getLogger(SearchSuggestionServiceImpl::class.java)
        private const val MIN_QUERY_LENGTH = 2
    }

    override fun suggest(partialQuery: String, limit: Int): List<SearchSuggestion> {
        if (partialQuery.length < MIN_QUERY_LENGTH) {
            return emptyList()
        }

        val effectiveLimit = limit.coerceIn(1, 50)

        return try {
            // First try the function-based approach
            suggestViaFunction(partialQuery, effectiveLimit)
        } catch (e: Exception) {
            log.warn("Function-based suggestions failed, trying direct query: {}", e.message)
            // Fallback to direct query if function doesn't exist
            suggestViaDirect(partialQuery, effectiveLimit)
        }
    }

    private fun suggestViaFunction(partialQuery: String, limit: Int): List<SearchSuggestion> {
        val sql = "SELECT term, source, similarity FROM search_suggest(:query, :limit)"

        @Suppress("UNCHECKED_CAST")
        val results = entityManager.createNativeQuery(sql)
            .setParameter("query", partialQuery)
            .setParameter("limit", limit)
            .resultList as List<Array<Any?>>

        return results.map { row ->
            SearchSuggestion(
                term = row[0] as String,
                source = row[1] as String,
                similarity = (row[2] as Number).toFloat()
            )
        }
    }

    private fun suggestViaDirect(partialQuery: String, limit: Int): List<SearchSuggestion> {
        // Direct query using LIKE for basic suggestions when pg_trgm isn't available
        val sql = """
            SELECT DISTINCT term, source, 1.0 as similarity
            FROM (
                SELECT title as term, 'title' as source FROM fsfile
                WHERE title IS NOT NULL AND LOWER(title) LIKE LOWER(:pattern)
                UNION ALL
                SELECT label as term, 'label' as source FROM fsfile
                WHERE label IS NOT NULL AND LOWER(label) LIKE LOWER(:pattern)
                UNION ALL
                SELECT author as term, 'author' as source FROM fsfile
                WHERE author IS NOT NULL AND LOWER(author) LIKE LOWER(:pattern)
            ) candidates
            LIMIT :limit
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val results = entityManager.createNativeQuery(sql)
            .setParameter("pattern", "%$partialQuery%")
            .setParameter("limit", limit)
            .resultList as List<Array<Any?>>

        return results.map { row ->
            SearchSuggestion(
                term = row[0] as String,
                source = row[1] as String,
                similarity = (row[2] as Number).toFloat()
            )
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            val sql = "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm'"
            val count = entityManager.createNativeQuery(sql).singleResult as Number
            count.toLong() > 0
        } catch (e: Exception) {
            log.warn("Failed to check pg_trgm availability: {}", e.message)
            false
        }
    }
}
