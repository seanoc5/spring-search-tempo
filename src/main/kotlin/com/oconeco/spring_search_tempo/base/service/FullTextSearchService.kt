package com.oconeco.spring_search_tempo.base.service

import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for PostgreSQL Full-Text Search across indexed content.
 *
 * Uses PostgreSQL's native FTS capabilities with:
 * - tsvector for document representation
 * - tsquery for search queries
 * - ts_rank for relevance ranking
 * - ts_headline for snippet generation
 */
interface FullTextSearchService {

    /**
     * Search across both fs_file and content_chunks tables.
     *
     * @param query Search query (supports & for AND, | for OR, ! for NOT, phrase:"exact phrase")
     * @param pageable Pagination and sorting parameters
     * @return Page of search results with ranking and snippets
     */
    fun searchAll(query: String, pageable: Pageable): Page<SearchResult>

    /**
     * Search only in fs_file table.
     *
     * @param query Search query
     * @param pageable Pagination and sorting parameters
     * @return Page of file search results
     */
    fun searchFiles(query: String, pageable: Pageable): Page<FileSearchResult>

    /**
     * Search only in content_chunks table.
     *
     * @param query Search query
     * @param pageable Pagination and sorting parameters
     * @return Page of chunk search results
     */
    fun searchChunks(query: String, pageable: Pageable): Page<ChunkSearchResult>
}

/**
 * Generic search result across all searchable content.
 */
data class SearchResult(
    val sourceTable: String,
    val id: Long,
    val uri: String,
    val label: String,
    val snippet: String,
    val rank: Float
)

/**
 * File-specific search result with additional file metadata.
 */
data class FileSearchResult(
    val id: Long,
    val uri: String,
    val label: String,
    val snippet: String,
    val rank: Float,
    val author: String? = null,
    val title: String? = null,
    val contentType: String? = null
)

/**
 * Chunk-specific search result with chunk context.
 */
data class ChunkSearchResult(
    val id: Long,
    val fileUri: String?,
    val fileLabel: String?,
    val chunkNumber: Int,
    val chunkType: String?,
    val snippet: String,
    val rank: Float
)

@Service
@Transactional(readOnly = true)
class FullTextSearchServiceImpl(
    private val entityManager: EntityManager
) : FullTextSearchService {

    companion object {
        private val log = LoggerFactory.getLogger(FullTextSearchServiceImpl::class.java)
    }

    override fun searchAll(query: String, pageable: Pageable): Page<SearchResult> {
        val sanitizedQuery = sanitizeQuery(query)

        log.debug("Searching all content for: {} (sanitized: {})", query, sanitizedQuery)

        // Combined search across fs_file and content_chunks
        val sql = """
            -- Search in fs_file
            SELECT
                'fs_file' as source_table,
                f.id,
                f.uri,
                f.label,
                ts_headline('english', COALESCE(f.body_text, f.label),
                           to_tsquery('english', :query),
                           'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
                ts_rank(f.fts_vector, to_tsquery('english', :query)) as rank
            FROM fs_file f
            WHERE f.fts_vector @@ to_tsquery('english', :query)

            UNION ALL

            -- Search in content_chunks
            SELECT
                'content_chunks' as source_table,
                c.id,
                COALESCE(f.uri, 'unknown') as uri,
                COALESCE(f.label, 'Chunk #' || c.chunk_number) as label,
                ts_headline('english', COALESCE(c.text, ''),
                           to_tsquery('english', :query),
                           'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
                ts_rank(c.fts_vector, to_tsquery('english', :query)) as rank
            FROM content_chunks c
            LEFT JOIN fs_file f ON c.concept_id = f.id
            WHERE c.fts_vector @@ to_tsquery('english', :query)

            ORDER BY rank DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val countSql = """
            SELECT COUNT(*) FROM (
                SELECT 1 FROM fs_file WHERE fts_vector @@ to_tsquery('english', :query)
                UNION ALL
                SELECT 1 FROM content_chunks WHERE fts_vector @@ to_tsquery('english', :query)
            ) combined
        """.trimIndent()

        try {
            val results = entityManager.createNativeQuery(sql)
                .setParameter("query", sanitizedQuery)
                .setParameter("limit", pageable.pageSize)
                .setParameter("offset", pageable.offset)
                .resultList
                .map { row ->
                    val cols = row as Array<*>
                    SearchResult(
                        sourceTable = cols[0] as String,
                        id = (cols[1] as Number).toLong(),
                        uri = cols[2] as String,
                        label = cols[3] as String,
                        snippet = cols[4] as String,
                        rank = (cols[5] as Number).toFloat()
                    )
                }

            val total = (entityManager.createNativeQuery(countSql)
                .setParameter("query", sanitizedQuery)
                .singleResult as Number).toLong()

            log.debug("Found {} total results, returning page {}", total, pageable.pageNumber)
            return PageImpl(results, pageable, total)

        } catch (e: Exception) {
            log.error("Error executing full-text search for query: {}", query, e)
            throw SearchException("Failed to execute search: ${e.message}", e)
        }
    }

    override fun searchFiles(query: String, pageable: Pageable): Page<FileSearchResult> {
        val sanitizedQuery = sanitizeQuery(query)

        log.debug("Searching files for: {} (sanitized: {})", query, sanitizedQuery)

        val sql = """
            SELECT
                f.id,
                f.uri,
                f.label,
                ts_headline('english', COALESCE(f.body_text, f.label),
                           to_tsquery('english', :query),
                           'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
                ts_rank(f.fts_vector, to_tsquery('english', :query)) as rank,
                f.author,
                f.title,
                f.content_type
            FROM fs_file f
            WHERE f.fts_vector @@ to_tsquery('english', :query)
            ORDER BY rank DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val countSql = """
            SELECT COUNT(*) FROM fs_file
            WHERE fts_vector @@ to_tsquery('english', :query)
        """.trimIndent()

        try {
            val results = entityManager.createNativeQuery(sql)
                .setParameter("query", sanitizedQuery)
                .setParameter("limit", pageable.pageSize)
                .setParameter("offset", pageable.offset)
                .resultList
                .map { row ->
                    val cols = row as Array<*>
                    FileSearchResult(
                        id = (cols[0] as Number).toLong(),
                        uri = cols[1] as String,
                        label = cols[2] as String,
                        snippet = cols[3] as String,
                        rank = (cols[4] as Number).toFloat(),
                        author = cols[5] as String?,
                        title = cols[6] as String?,
                        contentType = cols[7] as String?
                    )
                }

            val total = (entityManager.createNativeQuery(countSql)
                .setParameter("query", sanitizedQuery)
                .singleResult as Number).toLong()

            return PageImpl(results, pageable, total)

        } catch (e: Exception) {
            log.error("Error searching files for query: {}", query, e)
            throw SearchException("Failed to search files: ${e.message}", e)
        }
    }

    override fun searchChunks(query: String, pageable: Pageable): Page<ChunkSearchResult> {
        val sanitizedQuery = sanitizeQuery(query)

        log.debug("Searching chunks for: {} (sanitized: {})", query, sanitizedQuery)

        val sql = """
            SELECT
                c.id,
                f.uri,
                f.label,
                c.chunk_number,
                c.chunk_type,
                ts_headline('english', COALESCE(c.text, ''),
                           to_tsquery('english', :query),
                           'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
                ts_rank(c.fts_vector, to_tsquery('english', :query)) as rank
            FROM content_chunks c
            LEFT JOIN fs_file f ON c.concept_id = f.id
            WHERE c.fts_vector @@ to_tsquery('english', :query)
            ORDER BY rank DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val countSql = """
            SELECT COUNT(*) FROM content_chunks
            WHERE fts_vector @@ to_tsquery('english', :query)
        """.trimIndent()

        try {
            val results = entityManager.createNativeQuery(sql)
                .setParameter("query", sanitizedQuery)
                .setParameter("limit", pageable.pageSize)
                .setParameter("offset", pageable.offset)
                .resultList
                .map { row ->
                    val cols = row as Array<*>
                    ChunkSearchResult(
                        id = (cols[0] as Number).toLong(),
                        fileUri = cols[1] as String?,
                        fileLabel = cols[2] as String?,
                        chunkNumber = (cols[3] as Number).toInt(),
                        chunkType = cols[4] as String?,
                        snippet = cols[5] as String,
                        rank = (cols[6] as Number).toFloat()
                    )
                }

            val total = (entityManager.createNativeQuery(countSql)
                .setParameter("query", sanitizedQuery)
                .singleResult as Number).toLong()

            return PageImpl(results, pageable, total)

        } catch (e: Exception) {
            log.error("Error searching chunks for query: {}", query, e)
            throw SearchException("Failed to search chunks: ${e.message}", e)
        }
    }

    /**
     * Sanitize and prepare query for PostgreSQL tsquery format.
     * Converts simple search syntax to PostgreSQL tsquery syntax:
     * - Multiple words become AND queries: "foo bar" -> "foo & bar"
     * - Quoted phrases remain: "exact phrase" -> "'exact phrase'"
     * - Already formatted queries are passed through
     */
    private fun sanitizeQuery(query: String): String {
        if (query.isBlank()) {
            throw SearchException("Search query cannot be empty")
        }

        // If query already contains FTS operators, assume it's properly formatted
        if (query.contains(" & ") || query.contains(" | ") || query.contains(" ! ")) {
            return query
        }

        // Simple sanitization: split by spaces and join with &
        // This converts "foo bar" to "foo & bar"
        return query.trim()
            .split("""\s+""".toRegex())
            .filter { it.isNotBlank() }
            .joinToString(" & ")
    }
}

/**
 * Exception thrown when search operations fail.
 */
class SearchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
