package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO


interface ContentChunkService {

    fun count(): Long

    fun findAll(): List<ContentChunkDTO>

    fun `get`(id: Long): ContentChunkDTO

    fun create(contentChunkDTO: ContentChunkDTO): Long

    fun update(id: Long, contentChunkDTO: ContentChunkDTO)

    fun delete(id: Long)

    fun getContentChunkValues(): Map<Long, Long>

}
