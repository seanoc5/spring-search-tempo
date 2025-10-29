package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.FSFolder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository


interface FSFolderRepository : JpaRepository<FSFolder, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<FSFolder>

    fun existsByUri(uri: String?): Boolean

    fun findByUri(uri: String?): FSFolder?

}
