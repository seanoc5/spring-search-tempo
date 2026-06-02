package com.oconeco.spring_search_tempo.batch.nlp

import com.fasterxml.jackson.databind.ObjectMapper
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import com.oconeco.spring_search_tempo.base.service.ContentChunkMapper
import com.oconeco.spring_search_tempo.base.service.NLPService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Synchronously re-runs NLP analysis on a single `ContentChunk`, bypassing the
 * batch job. Used by the NLP coverage admin page's "Re-process chunk N" form.
 *
 * Clears the `nlpProcessedAt` marker before running the processor so the
 * existing `NLPChunkProcessor` (which skips already-processed chunks) will do
 * its work. Persists the updated entity on success.
 */
@Service
class NLPChunkReprocessor(
    private val chunkRepository: ContentChunkRepository,
    private val chunkMapper: ContentChunkMapper,
    private val nlpService: NLPService,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private val log = LoggerFactory.getLogger(NLPChunkReprocessor::class.java)
    }

    /**
     * Re-process the chunk with the given ID. Returns true on success,
     * false if the chunk doesn't exist or has no text to analyze.
     */
    @Transactional
    fun reprocess(chunkId: Long): Boolean {
        val chunk = chunkRepository.findById(chunkId).orElse(null) ?: run {
            log.warn("Cannot re-process chunk {}: not found", chunkId)
            return false
        }
        if (chunk.text.isNullOrBlank()) {
            log.warn("Cannot re-process chunk {}: no text to analyze", chunkId)
            return false
        }

        val dto = chunkMapper.toDto(chunk)
        dto.nlpProcessedAt = null  // Force NLPChunkProcessor to run.

        val processor = NLPChunkProcessor(nlpService, objectMapper, chunkMapper)
        val result = processor.process(dto) ?: return false

        chunk.apply {
            namedEntities = result.namedEntities
            tokenAnnotations = result.tokenAnnotations
            nouns = result.nouns
            verbs = result.verbs
            sentiment = result.sentiment
            sentimentScore = result.sentimentScore
            nlpProcessedAt = result.nlpProcessedAt
            parseTree = result.parseTree
            parseUd = result.parseUd
            conllu = result.conllu
        }
        chunkRepository.save(chunk)
        log.info("Re-processed NLP for chunk {}", chunkId)
        return true
    }
}
