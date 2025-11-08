package com.oconeco.spring_search_tempo.batch.nlp

import com.oconeco.spring_search_tempo.base.model.ContentChunksDTO
import com.oconeco.spring_search_tempo.base.service.NLPService
import com.oconeco.spring_search_tempo.base.service.NamedEntity
import com.oconeco.spring_search_tempo.base.service.POSTag
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import java.time.OffsetDateTime

/**
 * ItemProcessor for NLP analysis of content chunks.
 *
 * Processes chunks that:
 * - Have text content
 * - Have not been NLP processed yet (nlpProcessedAt is null)
 * - Are marked for ANALYZE processing level
 *
 * Performs:
 * - Named Entity Recognition (NER)
 * - Part-of-Speech (POS) tagging
 * - Sentiment analysis
 *
 * @param nlpService Service for NLP operations
 * @param objectMapper For JSON serialization of results
 * @param mapper ContentChunks mapper (not used here, kept for consistency)
 */
class NLPChunkProcessor(
    private val nlpService: NLPService,
    private val objectMapper: ObjectMapper,
    private val mapper: com.oconeco.spring_search_tempo.base.service.ContentChunksMapper
) : ItemProcessor<ContentChunksDTO, ContentChunksDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(NLPChunkProcessor::class.java)
        private const val MAX_TEXT_LENGTH = 10000  // Skip very long chunks
    }

    override fun process(item: ContentChunksDTO): ContentChunksDTO? {
        val text = item.text

        // Skip if no text or already processed
        if (text.isNullOrBlank()) {
            log.debug("Skipping chunk {} - no text", item.id)
            return null
        }

        if (item.nlpProcessedAt != null) {
            log.debug("Skipping chunk {} - already NLP processed", item.id)
            return null
        }

        // Skip very long texts (they take too long to process)
        if (text.length > MAX_TEXT_LENGTH) {
            log.warn("Skipping chunk {} - text too long ({} chars)", item.id, text.length)
            item.nlpProcessedAt = OffsetDateTime.now()  // Mark as processed to skip in future
            return item
        }

        try {
            log.debug("Processing chunk {} ({} chars)", item.id, text.length)

            // Perform NLP analysis
            val analysis = nlpService.analyze(text)

            // Extract and store named entities as JSON
            if (analysis.namedEntities.isNotEmpty()) {
                item.namedEntities = serializeNamedEntities(analysis.namedEntities)
                log.debug("Found {} named entities in chunk {}", analysis.namedEntities.size, item.id)
            }

            // Extract and store POS tags as JSON
            if (analysis.posTag.isNotEmpty()) {
                item.tokenAnnotations = serializePOSTags(analysis.posTag)

                // Extract nouns and verbs for quick access
                val nouns = analysis.posTag
                    .filter { it.tag.startsWith("NN") }  // NN, NNS, NNP, NNPS
                    .map { it.lemma }
                    .distinct()
                item.nouns = nouns.joinToString(", ")

                val verbs = analysis.posTag
                    .filter { it.tag.startsWith("VB") }  // VB, VBD, VBG, VBN, VBP, VBZ
                    .map { it.lemma }
                    .distinct()
                item.verbs = verbs.joinToString(", ")

                log.debug("Found {} nouns, {} verbs in chunk {}", nouns.size, verbs.size, item.id)
            }

            // Store sentiment
            analysis.sentiment?.let { sentiment ->
                item.sentiment = sentiment.sentiment
                item.sentimentScore = sentiment.score
                log.debug("Sentiment for chunk {}: {} (score: {})",
                    item.id, sentiment.sentiment, sentiment.score)
            }

            // Mark as processed
            item.nlpProcessedAt = OffsetDateTime.now()

            log.info("NLP processing complete for chunk {}: {} entities, {} tokens, sentiment: {}",
                item.id,
                analysis.namedEntities.size,
                analysis.posTag.size,
                analysis.sentiment?.sentiment ?: "N/A")

            return item

        } catch (e: Exception) {
            log.error("Error processing chunk {}: {}", item.id, e.message, e)
            // Mark as processed with timestamp to avoid reprocessing failed chunks
            item.nlpProcessedAt = OffsetDateTime.now()
            return item
        }
    }

    /**
     * Serialize named entities to JSON for storage.
     */
    private fun serializeNamedEntities(entities: List<NamedEntity>): String {
        return try {
            objectMapper.writeValueAsString(entities)
        } catch (e: Exception) {
            log.error("Failed to serialize named entities", e)
            "[]"
        }
    }

    /**
     * Serialize POS tags to JSON for storage.
     */
    private fun serializePOSTags(tags: List<POSTag>): String {
        return try {
            objectMapper.writeValueAsString(tags)
        } catch (e: Exception) {
            log.error("Failed to serialize POS tags", e)
            "[]"
        }
    }
}
