package com.oconeco.spring_search_tempo.base.model

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import com.oconeco.spring_search_tempo.base.service.NamedEntityDTO
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * View model for rendering NLP annotations on a content chunk in the FSFile detail page.
 *
 * Holds the chunk's text already split into [segments] — alternating plain and entity spans —
 * so the template can render entity links without doing HTML-escaping in SpEL.
 */
data class ChunkNlpView(
    val id: Long,
    val chunkNumber: Int?,
    val sentiment: String?,
    val sentimentScore: Double?,
    val nlpProcessed: Boolean,
    val segments: List<TextSegment>,
    val entityCount: Int,
    val nouns: String?,
    val verbs: String?,
) {
    /** Bootstrap badge class for the sentiment label. */
    val sentimentBadgeClass: String = when (sentiment?.uppercase()) {
        "POSITIVE" -> "bg-success"
        "NEGATIVE" -> "bg-danger"
        "NEUTRAL" -> "bg-secondary"
        null -> "bg-light text-dark border"
        else -> "bg-secondary"
    }

    /** Human label for the sentiment badge (handles null). */
    val sentimentLabel: String = sentiment ?: "—"

    /** Score formatted for tooltip; empty if no score. */
    val sentimentScoreFormatted: String =
        sentimentScore?.let { "%.2f".format(it) } ?: ""
}

/**
 * One run of chunk text. When [entityType] is non-null the segment is a named-entity
 * span — render as a link to [href]. Otherwise render the raw [text] as plain content.
 */
data class TextSegment(
    val text: String,
    val entityType: String? = null,
    val href: String? = null,
) {
    val isEntity: Boolean get() = entityType != null

    /** Bootstrap badge-style class keyed off the entity type. */
    val typeBadgeClass: String = when (entityType?.uppercase()) {
        "PERSON" -> "text-bg-primary"
        "ORGANIZATION" -> "text-bg-success"
        "LOCATION", "CITY", "STATE_OR_PROVINCE", "COUNTRY" -> "text-bg-info"
        "DATE", "TIME", "DURATION", "SET" -> "text-bg-warning"
        "MONEY", "PERCENT", "NUMBER", "ORDINAL" -> "text-bg-dark"
        null -> ""
        else -> "text-bg-secondary"
    }
}

object ChunkNlpViewFactory {

    private val entityListType = object : TypeReference<List<NamedEntityDTO>>() {}

    fun from(chunk: ContentChunk, objectMapper: ObjectMapper): ChunkNlpView {
        val text = chunk.text ?: ""
        val entities = parseEntities(chunk.namedEntities, objectMapper)
        val segments = buildSegments(text, entities)
        return ChunkNlpView(
            id = chunk.id ?: 0L,
            chunkNumber = chunk.chunkNumber,
            sentiment = chunk.sentiment,
            sentimentScore = chunk.sentimentScore,
            nlpProcessed = chunk.nlpProcessedAt != null,
            segments = segments,
            entityCount = entities.size,
            nouns = chunk.nouns,
            verbs = chunk.verbs,
        )
    }

    private fun parseEntities(json: String?, objectMapper: ObjectMapper): List<NamedEntityDTO> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            objectMapper.readValue(json, entityListType)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Split [text] into alternating plain/entity segments using each entity's offsets.
     * Skips any entity whose offsets are missing, out of range, or overlapping the
     * previous one — keeps fallback behaviour safe when annotations drift from the text.
     */
    internal fun buildSegments(text: String, entities: List<NamedEntityDTO>): List<TextSegment> {
        if (text.isEmpty()) return emptyList()
        if (entities.isEmpty()) return listOf(TextSegment(text))

        val ordered = entities
            .filter { it.startOffset != null && it.endOffset != null }
            .filter { it.startOffset!! in 0..text.length && it.endOffset!! in 0..text.length }
            .filter { it.endOffset!! > it.startOffset!! }
            .sortedBy { it.startOffset }

        val out = mutableListOf<TextSegment>()
        var cursor = 0
        for (ent in ordered) {
            val start = ent.startOffset!!
            val end = ent.endOffset!!
            if (start < cursor) continue
            if (start > cursor) out += TextSegment(text.substring(cursor, start))
            val entityText = text.substring(start, end)
            out += TextSegment(
                text = entityText,
                entityType = ent.type,
                href = "/search?q=" + URLEncoder.encode(entityText, StandardCharsets.UTF_8),
            )
            cursor = end
        }
        if (cursor < text.length) out += TextSegment(text.substring(cursor))
        return out
    }
}
