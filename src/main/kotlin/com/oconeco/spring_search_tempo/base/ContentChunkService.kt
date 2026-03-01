package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO


interface ContentChunkService {

    fun count(): Long

    fun findAll(): List<ContentChunkDTO>

    fun `get`(id: Long): ContentChunkDTO

    fun create(contentChunkDTO: ContentChunkDTO): Long

    /**
     * Bulk-create content chunks. Returns list of saved entity IDs.
     * More efficient than calling create() per item due to JDBC batching.
     */
    fun createBulk(dtos: List<ContentChunkDTO>): List<Long>

    fun update(id: Long, contentChunkDTO: ContentChunkDTO)

    fun delete(id: Long)

    fun getContentChunkValues(): Map<Long, Long>

    /**
     * Count chunks that have been NLP processed.
     */
    fun countNlpProcessed(): Long

    /**
     * Count chunks pending NLP processing.
     */
    fun countNlpPending(): Long

    /**
     * Get chunk counts grouped by Status (processing state).
     * @return Map of status name to count
     */
    fun countByStatus(): Map<String, Long>

    /**
     * Get chunk counts grouped by analysis level.
     * - INDEX: default, text indexed only
     * - NLP: INDEX + NLP processing done (nlpProcessedAt IS NOT NULL)
     * - EMBED: INDEX + NLP + vector embedding done (embedding IS NOT NULL)
     * @return Map of level name to count
     */
    fun countByAnalysisLevel(): Map<String, Long>

}
