package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.FSFolderDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface FSFolderService {

    fun count(): Long

    fun findAll(filter: String?, pageable: Pageable, showSkipped: Boolean = false): Page<FSFolderDTO>

    fun `get`(id: Long): FSFolderDTO

    fun create(fSFolderDTO: FSFolderDTO): Long

    fun update(id: Long, fSFolderDTO: FSFolderDTO)

    fun delete(id: Long)

    fun uriExists(uri: String?): Boolean

    fun getFSFolderValues(): Map<Long, Long>

}
