package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.ContentChunk
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository


interface ContentChunkRepository : JpaRepository<ContentChunk, Long> {

    fun findFirstByParentChunkIdAndIdNot(id: Long, currentId: Long?): ContentChunk?

    fun findFirstByConceptId(id: Long): ContentChunk?

    /**
     * Find chunks that have not been NLP processed yet and have text.
     * Used by NLP batch job to find chunks to process.
     */
    fun findByNlpProcessedAtIsNullAndTextIsNotNull(pageable: Pageable): Page<ContentChunk>

}
