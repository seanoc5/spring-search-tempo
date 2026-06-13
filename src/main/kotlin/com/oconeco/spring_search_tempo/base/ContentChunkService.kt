package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.ChunkNlpView
import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface ContentChunkService {

    /**
     * Build view models for ContentChunks linked to the given FSFile, ordered
     * by chunkNumber and paginated. Each view includes the chunk text already
     * split into plain/entity segments so templates can render entity links
     * without doing HTML manipulation in SpEL. Returns an empty page when the
     * file has no chunks (e.g. not yet chunked / LOCATE-only).
     *
     * Pagination keeps the response size bounded for heavily-chunked ANALYZE
     * documents (issue #82) — a 10MB sentence-chunked file can produce
     * hundreds of chunks.
     */
    fun findNlpViewsForFile(fileId: Long, pageable: Pageable): Page<ChunkNlpView>

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

    /**
     * Count distinct files with NLP-processed chunks, grouped by crawl config.
     * @return Map of crawlConfigId to file count
     */
    fun countFilesWithNlpByCrawlConfig(): Map<Long, Long>

    /**
     * Count distinct files with embedded chunks, grouped by crawl config.
     * @return Map of crawlConfigId to file count
     */
    fun countFilesWithEmbeddingByCrawlConfig(): Map<Long, Long>

}
