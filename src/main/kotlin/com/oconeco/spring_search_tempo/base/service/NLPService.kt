package com.oconeco.spring_search_tempo.base.service

import edu.stanford.nlp.ling.CoreAnnotations
import edu.stanford.nlp.ling.CoreLabel
import edu.stanford.nlp.pipeline.CoreDocument
import edu.stanford.nlp.pipeline.StanfordCoreNLP
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.Properties

/**
 * Service for Natural Language Processing using Stanford CoreNLP.
 *
 * Provides linguistic analysis capabilities including:
 * - Named Entity Recognition (NER) - Person, Organization, Location, etc.
 * - Part-of-Speech (POS) tagging
 * - Dependency parsing
 * - Sentiment analysis
 */
interface NLPService {

    /**
     * Perform comprehensive NLP analysis on text.
     *
     * @param text The text to analyze
     * @return NLPAnalysisResult containing all extracted information
     */
    fun analyze(text: String): NLPAnalysisResult

    /**
     * Extract named entities from text.
     *
     * @param text The text to analyze
     * @return List of named entities with their types
     */
    fun extractNamedEntities(text: String): List<NamedEntity>

    /**
     * Perform part-of-speech tagging on text.
     *
     * @param text The text to analyze
     * @return List of tokens with their POS tags
     */
    fun performPOSTagging(text: String): List<POSTag>

    /**
     * Analyze sentiment of text.
     *
     * @param text The text to analyze
     * @return Sentiment score and label
     */
    fun analyzeSentiment(text: String): SentimentResult
}

/**
 * Comprehensive NLP analysis result.
 */
data class NLPAnalysisResult(
    val text: String,
    val namedEntities: List<NamedEntity>,
    val posTag: List<POSTag>,
    val sentiment: SentimentResult?,
    val sentences: List<String>
)

/**
 * Named entity with type and text.
 */
data class NamedEntity(
    val text: String,
    val type: String,  // PERSON, ORGANIZATION, LOCATION, DATE, MONEY, etc.
    val startOffset: Int,
    val endOffset: Int
)

/**
 * Part-of-speech tag for a token.
 */
data class POSTag(
    val token: String,
    val tag: String,  // NN, VB, JJ, etc.
    val lemma: String
)

/**
 * Sentiment analysis result.
 */
data class SentimentResult(
    val sentiment: String,  // POSITIVE, NEGATIVE, NEUTRAL
    val score: Double  // Confidence score 0-1
)

/**
 * Stanford CoreNLP-based implementation of NLP service.
 *
 * Lazily initializes the pipeline to avoid long startup times.
 */
@Service
class StanfordNLPService : NLPService {

    companion object {
        private val log = LoggerFactory.getLogger(StanfordNLPService::class.java)
    }

    // Lazy initialization - pipeline takes ~5 seconds to load
    private val pipeline: StanfordCoreNLP by lazy {
        log.info("Initializing Stanford CoreNLP pipeline (this may take a few seconds)...")
        val props = Properties().apply {
            // Configure annotators to use
            setProperty("annotators", "tokenize,ssplit,pos,lemma,ner,parse,sentiment")
            setProperty("ner.useSUTime", "false")  // Disable SUTime for faster processing
            setProperty("parse.model", "edu/stanford/nlp/models/srparser/englishSR.ser.gz")
        }

        val startTime = System.currentTimeMillis()
        val nlp = StanfordCoreNLP(props)
        val duration = System.currentTimeMillis() - startTime

        log.info("Stanford CoreNLP pipeline initialized in {}ms", duration)
        nlp
    }

    override fun analyze(text: String): NLPAnalysisResult {
        if (text.isBlank()) {
            return NLPAnalysisResult(
                text = text,
                namedEntities = emptyList(),
                posTag = emptyList(),
                sentiment = null,
                sentences = emptyList()
            )
        }

        try {
            val document = CoreDocument(text)
            pipeline.annotate(document)

            val entities = extractNamedEntitiesFromDocument(document)
            val posTags = extractPOSTagsFromDocument(document)
            val sentiment = extractSentimentFromDocument(document)
            val sentences = document.sentences().map { it.text() }

            return NLPAnalysisResult(
                text = text,
                namedEntities = entities,
                posTag = posTags,
                sentiment = sentiment,
                sentences = sentences
            )
        } catch (e: Exception) {
            log.error("Error during NLP analysis", e)
            throw NLPException("Failed to analyze text: ${e.message}", e)
        }
    }

    override fun extractNamedEntities(text: String): List<NamedEntity> {
        if (text.isBlank()) return emptyList()

        return try {
            val document = CoreDocument(text)
            pipeline.annotate(document)
            extractNamedEntitiesFromDocument(document)
        } catch (e: Exception) {
            log.error("Error extracting named entities", e)
            emptyList()
        }
    }

    override fun performPOSTagging(text: String): List<POSTag> {
        if (text.isBlank()) return emptyList()

        return try {
            val document = CoreDocument(text)
            pipeline.annotate(document)
            extractPOSTagsFromDocument(document)
        } catch (e: Exception) {
            log.error("Error performing POS tagging", e)
            emptyList()
        }
    }

    override fun analyzeSentiment(text: String): SentimentResult {
        if (text.isBlank()) {
            return SentimentResult("NEUTRAL", 0.5)
        }

        return try {
            val document = CoreDocument(text)
            pipeline.annotate(document)
            extractSentimentFromDocument(document)
        } catch (e: Exception) {
            log.error("Error analyzing sentiment", e)
            SentimentResult("NEUTRAL", 0.5)
        }
    }

    private fun extractNamedEntitiesFromDocument(document: CoreDocument): List<NamedEntity> {
        val entities = mutableListOf<NamedEntity>()

        for (sentence in document.sentences()) {
            val tokens = sentence.tokens()
            var i = 0

            while (i < tokens.size) {
                val token = tokens[i]
                val nerTag = token.ner()

                // Skip "O" (outside) tags
                if (nerTag != "O") {
                    val entityTokens = mutableListOf<CoreLabel>()
                    entityTokens.add(token)

                    // Collect consecutive tokens with the same NER tag
                    var j = i + 1
                    while (j < tokens.size && tokens[j].ner() == nerTag) {
                        entityTokens.add(tokens[j])
                        j++
                    }

                    val entityText = entityTokens.joinToString(" ") { it.word() }
                    val startOffset = entityTokens.first().beginPosition()
                    val endOffset = entityTokens.last().endPosition()

                    entities.add(NamedEntity(entityText, nerTag, startOffset, endOffset))
                    i = j
                } else {
                    i++
                }
            }
        }

        return entities
    }

    private fun extractPOSTagsFromDocument(document: CoreDocument): List<POSTag> {
        return document.tokens().map { token ->
            POSTag(
                token = token.word(),
                tag = token.get(CoreAnnotations.PartOfSpeechAnnotation::class.java) ?: "UNKNOWN",
                lemma = token.lemma() ?: token.word()
            )
        }
    }

    private fun extractSentimentFromDocument(document: CoreDocument): SentimentResult {
        if (document.sentences().isEmpty()) {
            return SentimentResult("NEUTRAL", 0.5)
        }

        // Average sentiment across all sentences
        var totalScore = 0.0
        var sentimentCounts = mutableMapOf<String, Int>()

        for (sentence in document.sentences()) {
            val sentiment = sentence.sentiment()
            sentimentCounts[sentiment] = sentimentCounts.getOrDefault(sentiment, 0) + 1

            // Convert sentiment to numeric score
            val score = when (sentiment) {
                "Very negative" -> 0.0
                "Negative" -> 0.25
                "Neutral" -> 0.5
                "Positive" -> 0.75
                "Very positive" -> 1.0
                else -> 0.5
            }
            totalScore += score
        }

        val avgScore = totalScore / document.sentences().size

        // Determine overall sentiment
        val overallSentiment = when {
            avgScore < 0.33 -> "NEGATIVE"
            avgScore > 0.67 -> "POSITIVE"
            else -> "NEUTRAL"
        }

        return SentimentResult(overallSentiment, avgScore)
    }
}

/**
 * Exception thrown when NLP processing fails.
 */
class NLPException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
