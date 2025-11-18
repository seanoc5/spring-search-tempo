package com.oconeco.spring_search_tempo.batch.nlp

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.service.ContentChunkMapper
import com.oconeco.spring_search_tempo.base.service.NLPService
import com.oconeco.spring_search_tempo.base.service.NamedEntity
import com.oconeco.spring_search_tempo.base.service.NLPAnalysisResult
import com.oconeco.spring_search_tempo.base.service.POSTag
import com.oconeco.spring_search_tempo.base.service.SentimentResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Test for NLP chunk processor.
 *
 * Verifies that:
 * - Text chunks are analyzed for named entities, POS tags, and sentiment
 * - Results are properly serialized to JSON
 * - Nouns and verbs are extracted from POS tags
 * - Processing timestamp is set
 * - Empty or already-processed chunks are skipped
 */
class NLPChunkProcessorTest {

    private lateinit var nlpService: NLPService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var mapper: ContentChunkMapper
    private lateinit var processor: NLPChunkProcessor

    @BeforeEach
    fun setup() {
        nlpService = mock()
        objectMapper = ObjectMapper()
        mapper = mock()
        processor = NLPChunkProcessor(nlpService, objectMapper, mapper)
    }

    @Test
    fun `should process chunk with NLP analysis`() {
        // Given
        val chunk = ContentChunkDTO().apply {
            id = 1L
            text = "Apple Inc. announced new products in California."
        }

        val analysisResult = NLPAnalysisResult(
            text = chunk.text!!,
            namedEntities = listOf(
                NamedEntity("Apple Inc.", "ORGANIZATION", 0, 10),
                NamedEntity("California", "LOCATION", 40, 50)
            ),
            posTag = listOf(
                POSTag("Apple", "NNP", "Apple"),
                POSTag("Inc.", "NNP", "Inc."),
                POSTag("announced", "VBD", "announce"),
                POSTag("new", "JJ", "new"),
                POSTag("products", "NNS", "product"),
                POSTag("in", "IN", "in"),
                POSTag("California", "NNP", "California")
            ),
            sentiment = SentimentResult("NEUTRAL", 0.5),
            sentences = listOf("Apple Inc. announced new products in California.")
        )

        `when`(nlpService.analyze(Mockito.anyString())).thenReturn(analysisResult)

        // When
        val result = processor.process(chunk)

        // Then
        assertNotNull(result)
        assertNotNull(result!!.nlpProcessedAt)
        assertEquals("NEUTRAL", result.sentiment)
        assertEquals(0.5, result.sentimentScore)

        // Check named entities are serialized
        assertTrue(result.namedEntities!!.contains("Apple Inc."))
        assertTrue(result.namedEntities!!.contains("ORGANIZATION"))

        // Check nouns and verbs are extracted
        assertTrue(result.nouns!!.contains("Apple"))
        assertTrue(result.nouns!!.contains("product"))
        assertTrue(result.verbs!!.contains("announce"))
    }

    @Test
    fun `should skip chunk with null text`() {
        // Given
        val chunk = ContentChunkDTO().apply {
            id = 1L
            text = null
        }

        // When
        val result = processor.process(chunk)

        // Then
        assertNull(result)
    }

    @Test
    fun `should skip chunk with blank text`() {
        // Given
        val chunk = ContentChunkDTO().apply {
            id = 1L
            text = "   "
        }

        // When
        val result = processor.process(chunk)

        // Then
        assertNull(result)
    }

    @Test
    fun `should skip already processed chunk`() {
        // Given
        val chunk = ContentChunkDTO().apply {
            id = 1L
            text = "Some text"
            nlpProcessedAt = java.time.OffsetDateTime.now()
        }

        // When
        val result = processor.process(chunk)

        // Then
        assertNull(result)
    }

    @Test
    fun `should skip very long text chunks`() {
        // Given
        val longText = "word ".repeat(10000) // >10,000 chars
        val chunk = ContentChunkDTO().apply {
            id = 1L
            text = longText
        }

        // When
        val result = processor.process(chunk)

        // Then
        assertNotNull(result)
        assertNotNull(result!!.nlpProcessedAt) // Marked as processed
        assertNull(result.sentiment) // But not actually analyzed
    }

    @Test
    fun `should handle NLP service errors gracefully`() {
        // Given
        val chunk = ContentChunkDTO().apply {
            id = 1L
            text = "Some text"
        }

        `when`(nlpService.analyze(Mockito.anyString())).thenThrow(RuntimeException("NLP error"))

        // When
        val result = processor.process(chunk)

        // Then
        assertNotNull(result)
        assertNotNull(result!!.nlpProcessedAt) // Marked as processed even on error
    }
}
