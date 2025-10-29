package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.ContentChunksDTO


interface ContentChunksService {

    fun findAll(): List<ContentChunksDTO>

    fun `get`(id: Long): ContentChunksDTO

    fun create(contentChunksDTO: ContentChunksDTO): Long

    fun update(id: Long, contentChunksDTO: ContentChunksDTO)

    fun delete(id: Long)

    fun getContentChunksValues(): Map<Long, Long>

}
