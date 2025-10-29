package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.ContentChunks
import org.springframework.data.jpa.repository.JpaRepository


interface ContentChunksRepository : JpaRepository<ContentChunks, Long> {

    fun findFirstByParentChunkIdAndIdNot(id: Long, currentId: Long?): ContentChunks?

    fun findFirstByConceptId(id: Long): ContentChunks?

}
