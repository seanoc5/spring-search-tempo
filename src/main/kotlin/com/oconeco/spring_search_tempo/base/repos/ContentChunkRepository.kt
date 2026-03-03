package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.AnalysisStatus
import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime


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
     * - Parent object has analysisStatus in the provided list (typically ANALYZE, SEMANTIC)
     *
     * Parent sources supported:
     * - FSFile (`concept`)
     * - EmailMessage (`emailMessage`)
     * - BrowserBookmark (`browserBookmark`)
     * - OneDriveItem (`oneDriveItem`)
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
              OR
              (c.browserBookmark IS NOT NULL AND c.browserBookmark.analysisStatus IN :analysisStatuses)
              OR
              (c.oneDriveItem IS NOT NULL AND c.oneDriveItem.analysisStatus IN :analysisStatuses)
          )
    """)
    fun findChunksForNlpProcessing(
        @Param("analysisStatuses") analysisStatuses: List<AnalysisStatus>,
        pageable: Pageable
    ): Page<ContentChunk>

    /**
     * Find email-linked chunks eligible for NLP processing.
     * Filters by: account, received within cutoff date, non-null text, no 'junk' tag,
     * and optionally skips already NLP-processed chunks.
     */
    @Query("""
        SELECT c FROM ContentChunk c
        JOIN c.emailMessage em
        WHERE em.emailAccount.id = :accountId
          AND em.receivedDate >= :cutoffDate
          AND c.text IS NOT NULL
          AND (:forceRefresh = true OR c.nlpProcessedAt IS NULL)
          AND NOT EXISTS (SELECT 1 FROM em.tags t WHERE t.name = 'junk')
    """)
    fun findEmailChunksForNlp(
        @Param("accountId") accountId: Long,
        @Param("cutoffDate") cutoffDate: java.time.OffsetDateTime,
        @Param("forceRefresh") forceRefresh: Boolean,
        pageable: Pageable
    ): Page<ContentChunk>

    /**
     * Delete all content chunks for a specific email message.
     * Used by forceRefresh chunking to clear existing chunks before re-chunking.
     */
    @Modifying
    @Query("DELETE FROM ContentChunk c WHERE c.emailMessage.id = :emailMessageId")
    fun deleteByEmailMessageId(@Param("emailMessageId") emailMessageId: Long): Int

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

    /**
     * Delete all content chunks for an email account.
     */
    @Modifying
    @Query("""
        DELETE FROM ContentChunk c
        WHERE c.emailMessage.id IN (
            SELECT m.id FROM EmailMessage m WHERE m.emailAccount.id = :accountId
        )
    """)
    fun deleteByEmailAccountId(@Param("accountId") accountId: Long): Int

    /**
     * Delete all content chunks for a OneDrive account.
     */
    @Modifying
    @Query("""
        DELETE FROM ContentChunk c
        WHERE c.oneDriveItem.id IN (
            SELECT i.id FROM OneDriveItem i WHERE i.oneDriveAccount.id = :accountId
        )
    """)
    fun deleteByOneDriveAccountId(@Param("accountId") accountId: Long): Int

    /**
     * Find chunks containing a specific named entity text.
     * Uses PostgreSQL JSONB containment to search the named_entities JSON array.
     *
     * @param entityText The entity text to search for (case-sensitive)
     * @param pageable Pagination parameters
     * @return Page of ContentChunk entities containing the entity
     */
    @Query(
        value = """
            SELECT * FROM content_chunks c
            WHERE c.named_entities IS NOT NULL
              AND CAST(c.named_entities AS jsonb) @> CAST(?1 AS jsonb)
            ORDER BY c.id DESC
        """,
        countQuery = """
            SELECT COUNT(*) FROM content_chunks c
            WHERE c.named_entities IS NOT NULL
              AND CAST(c.named_entities AS jsonb) @> CAST(?1 AS jsonb)
        """,
        nativeQuery = true
    )
    fun findByNamedEntityText(
        entityJson: String,
        pageable: Pageable
    ): Page<ContentChunk>

    /**
     * Find chunks containing named entities of a specific type.
     * Uses PostgreSQL JSONB path query to filter by entity type.
     *
     * @param entityType The entity type (PERSON, ORGANIZATION, LOCATION, DATE, MONEY, etc.)
     * @param pageable Pagination parameters
     * @return Page of ContentChunk entities containing entities of that type
     */
    @Query(
        value = """
            SELECT * FROM content_chunks c
            WHERE c.named_entities IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM jsonb_array_elements(CAST(c.named_entities AS jsonb)) elem
                  WHERE elem->>'type' = ?1
              )
            ORDER BY c.id DESC
        """,
        countQuery = """
            SELECT COUNT(*) FROM content_chunks c
            WHERE c.named_entities IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM jsonb_array_elements(CAST(c.named_entities AS jsonb)) elem
                  WHERE elem->>'type' = ?1
              )
        """,
        nativeQuery = true
    )
    fun findByNamedEntityType(
        entityType: String,
        pageable: Pageable
    ): Page<ContentChunk>

    /**
     * Count chunks that have named entities (NLP processed).
     */
    fun countByNamedEntitiesIsNotNull(): Long

    /**
     * Count content fragments grouped by FSFile ID.
     * Returns pairs of [fileId, fragmentCount].
     */
    @Query("""
        SELECT c.concept.id, COUNT(c)
        FROM ContentChunk c
        WHERE c.concept.id IN :fileIds
        GROUP BY c.concept.id
    """)
    fun countGroupedByFileIds(@Param("fileIds") fileIds: Collection<Long>): List<Array<Any>>

    /**
     * Count chunks that have been NLP processed (nlpProcessedAt is not null).
     */
    fun countByNlpProcessedAtIsNotNull(): Long

    /**
     * Count chunks pending NLP processing (nlpProcessedAt is null, text is not null).
     */
    @Query("""
        SELECT COUNT(c) FROM ContentChunk c
        WHERE c.nlpProcessedAt IS NULL AND c.text IS NOT NULL
    """)
    fun countNlpPending(): Long

    /**
     * Count chunks by processing status.
     * Note: ContentChunk.status is a String field, not an enum.
     */
    fun countByStatus(status: String?): Long

    /**
     * Count chunks with vector embedding (embedding column is not null).
     * Used for EMBED analysis level.
     */
    @Query("""
        SELECT COUNT(c) FROM ContentChunk c
        WHERE c.embedding IS NOT NULL
    """)
    fun countWithEmbedding(): Long

    // ==================== EMBEDDING QUERIES ====================

    /**
     * Find email-linked chunks eligible for embedding generation.
     * Mirrors findEmailChunksForNlp but checks embeddingGeneratedAt instead.
     */
    @Query("""
        SELECT c FROM ContentChunk c
        JOIN c.emailMessage em
        WHERE em.emailAccount.id = :accountId
          AND em.receivedDate >= :cutoffDate
          AND c.text IS NOT NULL
          AND (:forceRefresh = true OR c.embeddingGeneratedAt IS NULL)
          AND NOT EXISTS (SELECT 1 FROM em.tags t WHERE t.name = 'junk')
    """)
    fun findEmailChunksForEmbedding(
        @Param("accountId") accountId: Long,
        @Param("cutoffDate") cutoffDate: OffsetDateTime,
        @Param("forceRefresh") forceRefresh: Boolean,
        pageable: Pageable
    ): Page<ContentChunk>

    /**
     * Find chunks eligible for embedding generation.
     *
     * Only returns chunks where:
     * - text is not null
     * - nlpProcessedAt is not null (NLP already completed)
     * - parent analysisStatus is in the provided list (typically ANALYZE/SEMANTIC)
     * - embeddingGeneratedAt is null (unless forceRefresh=true)
     */
    @Query("""
        SELECT c FROM ContentChunk c
        WHERE c.text IS NOT NULL
          AND c.nlpProcessedAt IS NOT NULL
          AND (:forceRefresh = true OR c.embeddingGeneratedAt IS NULL)
          AND (
              (c.concept IS NOT NULL AND c.concept.analysisStatus IN :analysisStatuses)
              OR
              (c.emailMessage IS NOT NULL AND c.emailMessage.analysisStatus IN :analysisStatuses)
              OR
              (c.browserBookmark IS NOT NULL AND c.browserBookmark.analysisStatus IN :analysisStatuses)
              OR
              (c.oneDriveItem IS NOT NULL AND c.oneDriveItem.analysisStatus IN :analysisStatuses)
          )
    """)
    fun findChunksForEmbedding(
        @Param("forceRefresh") forceRefresh: Boolean,
        @Param("analysisStatuses") analysisStatuses: List<AnalysisStatus>,
        pageable: Pageable
    ): Page<ContentChunk>

    /**
     * Count chunks that have been embedded (embeddingGeneratedAt is not null).
     */
    fun countByEmbeddingGeneratedAtIsNotNull(): Long

    /**
     * Count chunks pending embedding (embeddingGeneratedAt is null, text is not null).
     */
    @Query("""
        SELECT COUNT(c) FROM ContentChunk c
        WHERE c.embeddingGeneratedAt IS NULL
          AND c.text IS NOT NULL
          AND c.nlpProcessedAt IS NOT NULL
          AND (
              (c.concept IS NOT NULL AND c.concept.analysisStatus IN (
                  com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.ANALYZE,
                  com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.SEMANTIC
              ))
              OR
              (c.emailMessage IS NOT NULL AND c.emailMessage.analysisStatus IN (
                  com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.ANALYZE,
                  com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.SEMANTIC
              ))
              OR
              (c.browserBookmark IS NOT NULL AND c.browserBookmark.analysisStatus IN (
                  com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.ANALYZE,
                  com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.SEMANTIC
              ))
              OR
              (c.oneDriveItem IS NOT NULL AND c.oneDriveItem.analysisStatus IN (
                  com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.ANALYZE,
                  com.oconeco.spring_search_tempo.base.domain.AnalysisStatus.SEMANTIC
              ))
          )
    """)
    fun countEmbeddingPending(): Long

    /**
     * Update embedding via native SQL to bypass Hibernate vector type mapping.
     * The embedding string should be in pgvector format: [0.1,0.2,...].
     */
    @Modifying
    @Query(
        value = """
            UPDATE content_chunks
            SET embedding = CAST(:embedding AS vector),
                embedding_generated_at = :generatedAt,
                embedding_model = :modelName
            WHERE id = :id
        """,
        nativeQuery = true
    )
    fun updateEmbedding(
        @Param("id") id: Long,
        @Param("embedding") embedding: String,
        @Param("generatedAt") generatedAt: OffsetDateTime,
        @Param("modelName") modelName: String
    )

    // ==================== CRAWL CONFIG STATS ====================

    /**
     * Count distinct FSFiles with NLP-processed chunks, grouped by crawl config.
     * Returns pairs of [crawlConfigId, fileCount].
     */
    @Query("""
        SELECT jr.crawlConfig.id, COUNT(DISTINCT c.concept.id)
        FROM ContentChunk c
        JOIN c.concept f
        JOIN JobRun jr ON f.jobRunId = jr.id
        WHERE c.nlpProcessedAt IS NOT NULL
        GROUP BY jr.crawlConfig.id
    """)
    fun countFilesWithNlpGroupedByCrawlConfig(): List<Array<Any>>

    /**
     * Count distinct FSFiles with embedded chunks, grouped by crawl config.
     * Returns pairs of [crawlConfigId, fileCount].
     */
    @Query("""
        SELECT jr.crawlConfig.id, COUNT(DISTINCT c.concept.id)
        FROM ContentChunk c
        JOIN c.concept f
        JOIN JobRun jr ON f.jobRunId = jr.id
        WHERE c.embeddingGeneratedAt IS NOT NULL
        GROUP BY jr.crawlConfig.id
    """)
    fun countFilesWithEmbeddingGroupedByCrawlConfig(): List<Array<Any>>

}
