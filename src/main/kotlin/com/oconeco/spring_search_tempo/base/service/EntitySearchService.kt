package com.oconeco.spring_search_tempo.base.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Service for searching and aggregating named entities extracted by NLP processing.
 *
 * Provides capabilities to:
 * - Search chunks by entity text or type
 * - Aggregate top entities by frequency
 * - List available entity types
 */
@Service
class EntitySearchService(
    private val contentChunkRepository: ContentChunkRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Valid NER entity types from Stanford CoreNLP.
         */
        val VALID_ENTITY_TYPES = setOf(
            "PERSON", "ORGANIZATION", "LOCATION", "DATE", "TIME",
            "MONEY", "PERCENT", "NUMBER", "ORDINAL", "DURATION",
            "SET", "MISC", "CITY", "STATE_OR_PROVINCE", "COUNTRY",
            "NATIONALITY", "RELIGION", "TITLE", "IDEOLOGY",
            "CRIMINAL_CHARGE", "CAUSE_OF_DEATH", "URL", "EMAIL"
        )
    }

    /**
     * Find chunks containing a specific entity text.
     *
     * @param entityText The entity text to search for
     * @param entityType Optional entity type filter
     * @param pageable Pagination parameters
     * @return Page of EntitySearchResult
     */
    fun searchByEntity(
        entityText: String,
        entityType: String? = null,
        pageable: Pageable
    ): Page<EntitySearchResult> {
        log.debug("Searching for entity: text='{}', type='{}'", entityText, entityType)

        // Build JSON pattern for containment search
        val entityJson = if (entityType != null) {
            """[{"text": "$entityText", "type": "$entityType"}]"""
        } else {
            """[{"text": "$entityText"}]"""
        }

        val chunks = contentChunkRepository.findByNamedEntityText(entityJson, pageable)
        return chunks.map { toEntitySearchResult(it) }
    }

    /**
     * Find chunks containing entities of a specific type.
     *
     * @param entityType The entity type (PERSON, ORGANIZATION, LOCATION, etc.)
     * @param pageable Pagination parameters
     * @return Page of EntitySearchResult
     */
    fun searchByEntityType(
        entityType: String,
        pageable: Pageable
    ): Page<EntitySearchResult> {
        val normalizedType = entityType.uppercase()
        if (normalizedType !in VALID_ENTITY_TYPES) {
            log.warn("Unknown entity type requested: {}", entityType)
        }

        log.debug("Searching for entities of type: {}", normalizedType)
        val chunks = contentChunkRepository.findByNamedEntityType(normalizedType, pageable)
        return chunks.map { toEntitySearchResult(it) }
    }

    /**
     * Get the top N most frequent entities, optionally filtered by type.
     *
     * @param entityType Optional entity type filter
     * @param limit Maximum number of results (default 20)
     * @return List of EntityFrequency sorted by count descending
     */
    fun getTopEntities(entityType: String? = null, limit: Int = 20): List<EntityFrequency> {
        log.debug("Getting top {} entities, type filter: {}", limit, entityType)

        val sql = """
            SELECT
                elem->>'text' as entity_text,
                elem->>'type' as entity_type,
                COUNT(*) as frequency
            FROM content_chunks c,
                 jsonb_array_elements(c.named_entities::jsonb) elem
            WHERE c.named_entities IS NOT NULL
              ${if (entityType != null) "AND elem->>'type' = ?" else ""}
            GROUP BY elem->>'text', elem->>'type'
            ORDER BY frequency DESC
            LIMIT ?
        """

        val results = if (entityType != null) {
            jdbcTemplate.query(sql, { rs, _ ->
                EntityFrequency(
                    text = rs.getString("entity_text"),
                    type = rs.getString("entity_type"),
                    count = rs.getLong("frequency")
                )
            }, entityType.uppercase(), limit)
        } else {
            jdbcTemplate.query(sql, { rs, _ ->
                EntityFrequency(
                    text = rs.getString("entity_text"),
                    type = rs.getString("entity_type"),
                    count = rs.getLong("frequency")
                )
            }, limit)
        }

        return results
    }

    /**
     * Get counts of entities grouped by type.
     *
     * @return List of EntityTypeCount
     */
    fun getEntityTypeCounts(): List<EntityTypeCount> {
        log.debug("Getting entity type counts")

        val sql = """
            SELECT
                elem->>'type' as entity_type,
                COUNT(DISTINCT elem->>'text') as unique_entities,
                COUNT(*) as total_occurrences
            FROM content_chunks c,
                 jsonb_array_elements(c.named_entities::jsonb) elem
            WHERE c.named_entities IS NOT NULL
            GROUP BY elem->>'type'
            ORDER BY total_occurrences DESC
        """

        return jdbcTemplate.query(sql) { rs, _ ->
            EntityTypeCount(
                type = rs.getString("entity_type"),
                uniqueCount = rs.getLong("unique_entities"),
                totalOccurrences = rs.getLong("total_occurrences")
            )
        }
    }

    /**
     * Get processing statistics for entity extraction.
     */
    fun getEntityStats(): EntityStats {
        val totalChunks = contentChunkRepository.count()
        val chunksWithEntities = contentChunkRepository.countByNamedEntitiesIsNotNull()
        val typeCounts = getEntityTypeCounts()

        return EntityStats(
            totalChunks = totalChunks,
            chunksWithEntities = chunksWithEntities,
            entityTypes = typeCounts.map { it.type },
            entityTypeCounts = typeCounts
        )
    }

    private fun toEntitySearchResult(chunk: ContentChunk): EntitySearchResult {
        val entities = parseNamedEntities(chunk.namedEntities)
        return EntitySearchResult(
            chunkId = chunk.id!!,
            fileId = chunk.concept?.id,
            fileUri = chunk.concept?.uri,
            chunkNumber = chunk.chunkNumber ?: 0,
            textSnippet = chunk.text?.take(200) ?: "",
            entities = entities,
            sentiment = chunk.sentiment,
            sentimentScore = chunk.sentimentScore
        )
    }

    private fun parseNamedEntities(json: String?): List<NamedEntityDTO> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, object : TypeReference<List<NamedEntityDTO>>() {})
        } catch (e: Exception) {
            log.warn("Failed to parse named entities JSON: {}", e.message)
            emptyList()
        }
    }
}

/**
 * Named entity as stored in JSON.
 */
data class NamedEntityDTO(
    val text: String,
    val type: String,
    val startOffset: Int? = null,
    val endOffset: Int? = null
)

/**
 * Search result containing chunk info and its entities.
 */
data class EntitySearchResult(
    val chunkId: Long,
    val fileId: Long?,
    val fileUri: String?,
    val chunkNumber: Int,
    val textSnippet: String,
    val entities: List<NamedEntityDTO>,
    val sentiment: String?,
    val sentimentScore: Double?
)

/**
 * Entity with its frequency count.
 */
data class EntityFrequency(
    val text: String,
    val type: String,
    val count: Long
)

/**
 * Entity type with counts.
 */
data class EntityTypeCount(
    val type: String,
    val uniqueCount: Long,
    val totalOccurrences: Long
)

/**
 * Overall entity extraction statistics.
 */
data class EntityStats(
    val totalChunks: Long,
    val chunksWithEntities: Long,
    val entityTypes: List<String>,
    val entityTypeCounts: List<EntityTypeCount>
)
