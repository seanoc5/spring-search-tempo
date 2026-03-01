package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.repos.ContentChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter


/**
 * ItemWriter that updates ContentChunk entities with NLP processing results.
 *
 * Uses bulk findAllById + saveAll for efficiency instead of per-item findById + save.
 *
 * @param contentChunkRepository Repository for finding and saving content chunks
 */
class EmailNLPWriter(
    private val contentChunkRepository: ContentChunkRepository
) : ItemWriter<ContentChunkDTO> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailNLPWriter::class.java)
    }

    private var totalChunksSaved = 0

    override fun write(chunk: Chunk<out ContentChunkDTO>) {
        val ids = chunk.items.mapNotNull { it.id }
        if (ids.isEmpty()) return

        val entities = contentChunkRepository.findAllById(ids).associateBy { it.id }

        val updated = chunk.items.mapNotNull { dto ->
            entities[dto.id]?.apply {
                namedEntities = dto.namedEntities
                tokenAnnotations = dto.tokenAnnotations
                nouns = dto.nouns
                verbs = dto.verbs
                sentiment = dto.sentiment
                sentimentScore = dto.sentimentScore
                nlpProcessedAt = dto.nlpProcessedAt
                parseTree = dto.parseTree
                parseUd = dto.parseUd
                conllu = dto.conllu
            }
        }

        if (updated.isNotEmpty()) {
            contentChunkRepository.saveAll(updated)
            totalChunksSaved += updated.size
            log.debug("EmailNLPWriter: Saved {} chunks (total: {})", updated.size, totalChunksSaved)
        }

        val missing = ids.size - updated.size
        if (missing > 0) {
            log.warn("EmailNLPWriter: {} chunk IDs not found in database", missing)
        }
    }
}
