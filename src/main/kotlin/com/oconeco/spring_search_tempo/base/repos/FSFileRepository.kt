package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.FSFile
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository


interface FSFileRepository : JpaRepository<FSFile, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<FSFile>

    fun findFirstByFsFolderId(id: Long): FSFile?

    fun existsByUri(uri: String?): Boolean

}
