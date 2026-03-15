package com.oconeco.spring_search_tempo.base.service

import com.oconeco.spring_search_tempo.base.model.SearchFilterDTO
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
     * Search across both fsfile and content_chunks tables.
     *
     * @param query Search query (supports & for AND, | for OR, ! for NOT, phrase:"exact phrase")
     * @param pageable Pagination and sorting parameters
     * @return Page of search results with ranking and snippets
     */
    fun searchAll(query: String, pageable: Pageable): Page<SearchResult>

    /**
     * Search only in fsfile table.
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

    /**
     * Search content_chunks with optional sentiment filter.
     *
     * @param query Search query
     * @param sentiment Optional sentiment filter: "POSITIVE", "NEGATIVE", "NEUTRAL", or null for all
     * @param pageable Pagination and sorting parameters
     * @return Page of chunk search results with NLP data
     */
    fun searchChunks(query: String, sentiment: String?, pageable: Pageable): Page<ChunkSearchResult>

    /**
     * Search with configurable filters.
     *
     * Searches across selected content types (files, emails, chunks) with optional
     * sentiment and category filters.
     *
     * @param filter Search filter containing query, content types, and optional filters
     * @param pageable Pagination and sorting parameters
     * @return Page of unified search results
     */
    fun searchWithFilters(filter: SearchFilterDTO, pageable: Pageable): Page<SearchResult>
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
 * Chunk-specific search result with chunk context and NLP data.
 */
data class ChunkSearchResult(
    val id: Long,
    val fileUri: String?,
    val fileLabel: String?,
    val chunkNumber: Int,
    val chunkType: String?,
    val snippet: String,
    val rank: Float,
    val sentiment: String? = null,
    val sentimentScore: Double? = null,
    val namedEntities: String? = null
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

        // Combined search across fsfile and content_chunks
        val sql = """
            -- Search in fsfile
            SELECT
                'fsfile' as source_table,
                f.id,
                f.uri,
                f.label,
                ts_headline('english', COALESCE(f.body_text, f.label),
                           to_tsquery('english', :query),
                           'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
                ts_rank(f.fts_vector, to_tsquery('english', :query)) as rank
            FROM fsfile f
            WHERE f.fts_vector @@ to_tsquery('english', :query)

            UNION ALL

            -- Search in one_drive_item
            SELECT
                'one_drive_item' as source_table,
                odi.id,
                odi.uri,
                COALESCE(odi.label, odi.item_name, 'OneDrive Item') as label,
                ts_headline('english', COALESCE(odi.body_text, odi.item_name),
                           to_tsquery('english', :query),
                           'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
                ts_rank(odi.fts_vector, to_tsquery('english', :query)) as rank
            FROM one_drive_item odi
            WHERE odi.fts_vector @@ to_tsquery('english', :query)
              AND odi.is_deleted = false

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
            LEFT JOIN fsfile f ON c.concept_id = f.id
            WHERE c.fts_vector @@ to_tsquery('english', :query)

            ORDER BY rank DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val countSql = """
            SELECT COUNT(*) FROM (
                SELECT 1 FROM fsfile WHERE fts_vector @@ to_tsquery('english', :query)
                UNION ALL
                SELECT 1 FROM one_drive_item WHERE fts_vector @@ to_tsquery('english', :query) AND is_deleted = false
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
            FROM fsfile f
            WHERE f.fts_vector @@ to_tsquery('english', :query)
            ORDER BY rank DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val countSql = """
            SELECT COUNT(*) FROM fsfile
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
        return searchChunks(query, null, pageable)
    }

    override fun searchChunks(query: String, sentiment: String?, pageable: Pageable): Page<ChunkSearchResult> {
        val sanitizedQuery = sanitizeQuery(query)
        val validSentiment = validateSentiment(sentiment)

        log.debug("Searching chunks for: {} (sanitized: {}), sentiment: {}", query, sanitizedQuery, validSentiment)

        val sentimentCondition = if (validSentiment != null) "AND c.sentiment = :sentiment" else ""

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
                ts_rank(c.fts_vector, to_tsquery('english', :query)) as rank,
                c.sentiment,
                c.sentiment_score,
                c.named_entities
            FROM content_chunks c
            LEFT JOIN fsfile f ON c.concept_id = f.id
            WHERE c.fts_vector @@ to_tsquery('english', :query)
            $sentimentCondition
            ORDER BY rank DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val countSql = """
            SELECT COUNT(*) FROM content_chunks c
            WHERE c.fts_vector @@ to_tsquery('english', :query)
            $sentimentCondition
        """.trimIndent()

        try {
            val resultsQuery = entityManager.createNativeQuery(sql)
                .setParameter("query", sanitizedQuery)
                .setParameter("limit", pageable.pageSize)
                .setParameter("offset", pageable.offset)

            val countQuery = entityManager.createNativeQuery(countSql)
                .setParameter("query", sanitizedQuery)

            if (validSentiment != null) {
                resultsQuery.setParameter("sentiment", validSentiment)
                countQuery.setParameter("sentiment", validSentiment)
            }

            val results = resultsQuery.resultList
                .map { row ->
                    val cols = row as Array<*>
                    ChunkSearchResult(
                        id = (cols[0] as Number).toLong(),
                        fileUri = cols[1] as String?,
                        fileLabel = cols[2] as String?,
                        chunkNumber = (cols[3] as Number).toInt(),
                        chunkType = cols[4] as String?,
                        snippet = cols[5] as String,
                        rank = (cols[6] as Number).toFloat(),
                        sentiment = cols[7] as String?,
                        sentimentScore = (cols[8] as Number?)?.toDouble(),
                        namedEntities = cols[9] as String?
                    )
                }

            val total = (countQuery.singleResult as Number).toLong()

            return PageImpl(results, pageable, total)

        } catch (e: Exception) {
            log.error("Error searching chunks for query: {}, sentiment: {}", query, sentiment, e)
            throw SearchException("Failed to search chunks: ${e.message}", e)
        }
    }

    /**
     * Validate sentiment filter value.
     * @return Validated sentiment or null if invalid/empty
     */
    private fun validateSentiment(sentiment: String?): String? {
        if (sentiment.isNullOrBlank()) return null
        val normalized = sentiment.uppercase().trim()
        return if (normalized in listOf("POSITIVE", "NEGATIVE", "NEUTRAL")) normalized else null
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

    override fun searchWithFilters(filter: SearchFilterDTO, pageable: Pageable): Page<SearchResult> {
        val sanitizedQuery = sanitizeQuery(filter.query)

        log.debug("Searching with filters: types={}, sentiment={}, category={}, fromDate={}, toDate={}, author={}",
            filter.contentTypes, filter.sentiment, filter.emailCategory, filter.fromDate, filter.toDate, filter.author)

        val sqlParts = mutableListOf<String>()

        // Build UNION query based on selected content types
        if (filter.includeFiles()) {
            sqlParts.add(buildFileSearchSql(filter))
        }

        if (filter.includeEmails()) {
            sqlParts.add(buildEmailSearchSql(filter))
        }

        if (filter.includeOneDrive()) {
            sqlParts.add(buildOneDriveSearchSql(filter))
        }

        if (filter.includeChunks()) {
            sqlParts.add(buildChunkSearchSql(filter))
        }

        if (sqlParts.isEmpty()) {
            return PageImpl(emptyList(), pageable, 0)
        }

        val sql = """
            ${sqlParts.joinToString("\n\nUNION ALL\n\n")}
            ORDER BY rank DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        // Build count query
        val countParts = mutableListOf<String>()
        if (filter.includeFiles()) {
            countParts.add(buildFileCountSql(filter))
        }
        if (filter.includeEmails()) {
            countParts.add(buildEmailCountSql(filter))
        }
        if (filter.includeOneDrive()) {
            countParts.add(buildOneDriveCountSql(filter))
        }
        if (filter.includeChunks()) {
            countParts.add(buildChunkCountSql(filter))
        }

        val countSql = """
            SELECT COUNT(*) FROM (
                ${countParts.joinToString("\nUNION ALL\n")}
            ) combined
        """.trimIndent()

        try {
            val resultsQuery = entityManager.createNativeQuery(sql)
                .setParameter("query", sanitizedQuery)
                .setParameter("limit", pageable.pageSize)
                .setParameter("offset", pageable.offset)

            val countQuery = entityManager.createNativeQuery(countSql)
                .setParameter("query", sanitizedQuery)

            // Set optional filter parameters
            setFilterParameters(resultsQuery, filter)
            setFilterParameters(countQuery, filter)

            val results = resultsQuery.resultList.map { row ->
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

            val total = (countQuery.singleResult as Number).toLong()

            log.debug("Found {} results with filters", total)
            return PageImpl(results, pageable, total)

        } catch (e: Exception) {
            log.error("Error searching with filters: {}", filter, e)
            throw SearchException("Failed to search with filters: ${e.message}", e)
        }
    }

    private fun setFilterParameters(query: jakarta.persistence.Query, filter: SearchFilterDTO) {
        if (filter.emailCategory != null && filter.includeEmails()) {
            query.setParameter("category", filter.emailCategory.name)
        }
        if (filter.sentiment != null && filter.includeChunks()) {
            val validSentiment = validateSentiment(filter.sentiment)
            if (validSentiment != null) {
                query.setParameter("sentiment", validSentiment)
            }
        }
        if (filter.fromDate != null) {
            query.setParameter("fromDate", filter.fromDate)
        }
        if (filter.toDate != null) {
            query.setParameter("toDate", filter.toDate)
        }
        if (filter.author != null) {
            query.setParameter("author", "%${filter.author}%")
        }
    }

    private fun buildFileSearchSql(filter: SearchFilterDTO): String {
        val conditions = mutableListOf<String>()
        if (filter.fromDate != null) conditions.add("f.last_modified >= CAST(:fromDate AS DATE)")
        if (filter.toDate != null) conditions.add("f.last_modified <= CAST(:toDate AS DATE)")
        if (filter.author != null) conditions.add("f.author ILIKE :author")
        val extraConditions = if (conditions.isNotEmpty()) "AND ${conditions.joinToString(" AND ")}" else ""

        return """
            SELECT
                'fsfile' as source_table,
                f.id,
                f.uri,
                f.label,
                ts_headline('english', COALESCE(f.body_text, f.label),
                           to_tsquery('english', :query),
                           'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
                ts_rank(f.fts_vector, to_tsquery('english', :query)) as rank
            FROM fsfile f
            WHERE f.fts_vector @@ to_tsquery('english', :query)
            $extraConditions
        """.trimIndent()
    }

    private fun buildFileCountSql(filter: SearchFilterDTO): String {
        val conditions = mutableListOf<String>()
        if (filter.fromDate != null) conditions.add("last_modified >= CAST(:fromDate AS DATE)")
        if (filter.toDate != null) conditions.add("last_modified <= CAST(:toDate AS DATE)")
        if (filter.author != null) conditions.add("author ILIKE :author")
        val extraConditions = if (conditions.isNotEmpty()) " AND ${conditions.joinToString(" AND ")}" else ""
        return "SELECT 1 FROM fsfile WHERE fts_vector @@ to_tsquery('english', :query)$extraConditions"
    }

    private fun buildEmailSearchSql(filter: SearchFilterDTO): String {
        val conditions = mutableListOf<String>()
        if (filter.emailCategory != null) conditions.add("e.category = :category")
        if (filter.fromDate != null) conditions.add("e.received_at >= CAST(:fromDate AS DATE)")
        if (filter.toDate != null) conditions.add("e.received_at <= CAST(:toDate AS DATE)")
        if (filter.author != null) conditions.add("e.sender ILIKE :author")
        val extraConditions = if (conditions.isNotEmpty()) "AND ${conditions.joinToString(" AND ")}" else ""

        return """
            SELECT
                'email_message' as source_table,
                e.id,
                COALESCE(e.message_id, 'email:' || e.id) as uri,
                COALESCE(e.subject, 'Email #' || e.id) as label,
                ts_headline('english', COALESCE(e.body_text, e.subject),
                           to_tsquery('english', :query),
                           'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
                ts_rank(e.fts_vector, to_tsquery('english', :query)) as rank
            FROM email_message e
            WHERE e.fts_vector @@ to_tsquery('english', :query)
            $extraConditions
        """.trimIndent()
    }

    private fun buildEmailCountSql(filter: SearchFilterDTO): String {
        val conditions = mutableListOf<String>()
        if (filter.emailCategory != null) conditions.add("category = :category")
        if (filter.fromDate != null) conditions.add("received_at >= CAST(:fromDate AS DATE)")
        if (filter.toDate != null) conditions.add("received_at <= CAST(:toDate AS DATE)")
        if (filter.author != null) conditions.add("sender ILIKE :author")
        val extraConditions = if (conditions.isNotEmpty()) " AND ${conditions.joinToString(" AND ")}" else ""
        return "SELECT 1 FROM email_message WHERE fts_vector @@ to_tsquery('english', :query)$extraConditions"
    }

    private fun buildOneDriveSearchSql(filter: SearchFilterDTO): String {
        val conditions = mutableListOf<String>()
        if (filter.fromDate != null) conditions.add("odi.last_modified_date_time >= CAST(:fromDate AS DATE)")
        if (filter.toDate != null) conditions.add("odi.last_modified_date_time <= CAST(:toDate AS DATE)")
        if (filter.author != null) conditions.add("odi.created_by ILIKE :author")
        val extraConditions = if (conditions.isNotEmpty()) "AND ${conditions.joinToString(" AND ")}" else ""

        return """
            SELECT
                'one_drive_item' as source_table,
                odi.id,
                odi.uri,
                COALESCE(odi.label, odi.item_name, 'OneDrive Item') as label,
                ts_headline('english', COALESCE(odi.body_text, odi.item_name),
                           to_tsquery('english', :query),
                           'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
                ts_rank(odi.fts_vector, to_tsquery('english', :query)) as rank
            FROM one_drive_item odi
            WHERE odi.fts_vector @@ to_tsquery('english', :query)
              AND odi.is_deleted = false
            $extraConditions
        """.trimIndent()
    }

    private fun buildOneDriveCountSql(filter: SearchFilterDTO): String {
        val conditions = mutableListOf<String>()
        if (filter.fromDate != null) conditions.add("last_modified_date_time >= CAST(:fromDate AS DATE)")
        if (filter.toDate != null) conditions.add("last_modified_date_time <= CAST(:toDate AS DATE)")
        if (filter.author != null) conditions.add("created_by ILIKE :author")
        val extraConditions = if (conditions.isNotEmpty()) " AND ${conditions.joinToString(" AND ")}" else ""
        return "SELECT 1 FROM one_drive_item WHERE fts_vector @@ to_tsquery('english', :query) AND is_deleted = false$extraConditions"
    }

    private fun buildChunkSearchSql(filter: SearchFilterDTO): String {
        val validSentiment = validateSentiment(filter.sentiment)
        val conditions = mutableListOf<String>()
        if (validSentiment != null) conditions.add("c.sentiment = :sentiment")
        // Note: chunks don't have their own date/author - they inherit from source
        val extraConditions = if (conditions.isNotEmpty()) "AND ${conditions.joinToString(" AND ")}" else ""

        return """
            SELECT
                'content_chunks' as source_table,
                c.id,
                COALESCE(f.uri, em.message_id, odi.uri, 'unknown') as uri,
                COALESCE(f.label, em.subject, odi.item_name, 'Chunk #' || c.chunk_number) as label,
                ts_headline('english', COALESCE(c.text, ''),
                           to_tsquery('english', :query),
                           'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
                ts_rank(c.fts_vector, to_tsquery('english', :query)) as rank
            FROM content_chunks c
            LEFT JOIN fsfile f ON c.concept_id = f.id
            LEFT JOIN email_message em ON c.email_message_id = em.id
            LEFT JOIN one_drive_item odi ON c.one_drive_item_id = odi.id
            WHERE c.fts_vector @@ to_tsquery('english', :query)
            $extraConditions
        """.trimIndent()
    }

    private fun buildChunkCountSql(filter: SearchFilterDTO): String {
        val validSentiment = validateSentiment(filter.sentiment)
        val sentimentCondition = if (validSentiment != null) " AND sentiment = :sentiment" else ""
        return "SELECT 1 FROM content_chunks WHERE fts_vector @@ to_tsquery('english', :query)$sentimentCondition"
    }
}

/**
 * Exception thrown when search operations fail.
 */
class SearchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
