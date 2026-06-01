package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.MirrorError
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface MirrorErrorRepository : JpaRepository<MirrorError, Long> {

    fun findByMirrorConfigIdAndJobRunIdOrderByOccurredAtAsc(
        mirrorConfigId: Long,
        jobRunId: Long,
        pageable: Pageable
    ): Page<MirrorError>

    fun findByMirrorConfigIdOrderByOccurredAtDesc(
        mirrorConfigId: Long,
        pageable: Pageable
    ): Page<MirrorError>

    fun findByMirrorConfigIdAndRetryable(
        mirrorConfigId: Long,
        retryable: Boolean
    ): List<MirrorError>

    fun countByMirrorConfigIdAndJobRunId(mirrorConfigId: Long, jobRunId: Long): Long

    fun countByMirrorConfigIdAndJobRunIdAndRetryable(
        mirrorConfigId: Long,
        jobRunId: Long,
        retryable: Boolean
    ): Long

    fun countByMirrorConfigIdAndJobRunIdAndSourceFolder(
        mirrorConfigId: Long,
        jobRunId: Long,
        sourceFolder: String
    ): Long

    /**
     * Streaming iterator for CSV export. JPA `Stream` keeps the cursor
     * open, so the caller can flush rows to the response without
     * materializing the whole result set.
     */
    @Query("""
        SELECT e FROM MirrorError e
        WHERE e.mirrorConfigId = :mirrorConfigId
          AND e.jobRunId = :jobRunId
        ORDER BY e.occurredAt ASC, e.id ASC
    """)
    fun streamByMirrorConfigAndJobRun(
        @Param("mirrorConfigId") mirrorConfigId: Long,
        @Param("jobRunId") jobRunId: Long
    ): java.util.stream.Stream<MirrorError>

    @Modifying
    @Transactional
    fun deleteByMirrorConfigIdAndJobRunId(mirrorConfigId: Long, jobRunId: Long): Int
}
