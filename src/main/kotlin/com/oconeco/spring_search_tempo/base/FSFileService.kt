package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.FSFileDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface FSFileService {

    fun count(): Long

    fun findAll(filter: String?, pageable: Pageable, showSkipped: Boolean = false): Page<FSFileDTO>

    fun `get`(id: Long): FSFileDTO

    fun create(fSFileDTO: FSFileDTO): Long

    fun update(id: Long, fSFileDTO: FSFileDTO)

    fun delete(id: Long)

    fun uriExists(uri: String?): Boolean

    fun getFSFileValues(): Map<Long, Long>

    /**
     * Find files with non-null bodyText for chunking.
     * Used by batch processing to retrieve files that need text chunking.
     */
    fun findFilesWithBodyText(pageable: Pageable): Page<FSFileDTO>

}
