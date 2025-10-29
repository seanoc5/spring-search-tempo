package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.SpringUser
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository


interface SpringUserRepository : JpaRepository<SpringUser, Long> {

    fun findByLabelIgnoreCase(label: String): SpringUser?

    fun findAllById(id: Long?, pageable: Pageable): Page<SpringUser>

    fun existsByLabel(label: String?): Boolean

}
