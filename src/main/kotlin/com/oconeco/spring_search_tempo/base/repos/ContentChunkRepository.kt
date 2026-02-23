package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param


interface ContentChunkRepository : JpaRepository<ContentChunk, Long> {

    fun findFirstByParentChunkIdAndIdNot(id: Long, currentId: Long?): ContentChunk?

    fun findFirstByConceptId(id: Long): ContentChunk?

    fun findFirstByEmailMessageId(id: Long): ContentChunk?

    /**
     * Find chunks that have not been NLP processed yet and have text.
     * Used by NLP batch job to find chunks to process.
     * @deprecated Use findChunksForNlpProcessing instead to respect parent analysisStatus
     */
    @Deprecated("Use findChunksForNlpProcessing instead", ReplaceWith("findChunksForNlpProcessing(analysisStatuses, pageable)"))
    fun findByNlpProcessedAtIsNullAndTextIsNotNull(pageable: Pageable): Page<ContentChunk>

    /**
     * Find chunks eligible for NLP processing based on parent file's analysisStatus.
     *
     * Only processes chunks where:
     * - nlpProcessedAt is NULL (not yet processed)
     * - text is NOT NULL (has content to analyze)
     * - Parent FSFile has analysisStatus in the provided list (typically ANALYZE, SEMANTIC)
     *
     * Note: Chunks from EmailMessage parents are also included if their parent
     * has the appropriate analysisStatus.
     *
     * @param analysisStatuses List of AnalysisStatus values that qualify for NLP (e.g., ANALYZE, SEMANTIC)
     * @param pageable Pagination parameters
     * @return Page of ContentChunk entities eligible for NLP processing
     */
    @Query("""
        SELECT c FROM ContentChunk c
        WHERE c.nlpProcessedAt IS NULL
          AND c.text IS NOT NULL
          AND (
              (c.concept IS NOT NULL AND c.concept.analysisStatus IN :analysisStatuses)
              OR
              (c.emailMessage IS NOT NULL AND c.emailMessage.analysisStatus IN :analysisStatuses)
          )
    """)
    fun findChunksForNlpProcessing(
        @Param("analysisStatuses") analysisStatuses: List<AnalysisStatus>,
        pageable: Pageable
    ): Page<ContentChunk>

    /**
     * Delete all content chunks belonging to files that were crawled by a specific crawl config.
     * Must be called before deleting FSFiles due to foreign key constraints.
     *
     * @param crawlConfigId The crawl config whose chunks should be deleted
     * @return The number of chunks deleted
     */
    @Modifying
    @Query("""
        DELETE FROM ContentChunk c
        WHERE c.concept.id IN (
            SELECT f.id FROM FSFile f WHERE f.jobRunId IN (
                SELECT jr.id FROM JobRun jr WHERE jr.crawlConfig.id = :crawlConfigId
            )
        )
    """)
    fun deleteByCrawlConfigId(@Param("crawlConfigId") crawlConfigId: Long): Int

}
