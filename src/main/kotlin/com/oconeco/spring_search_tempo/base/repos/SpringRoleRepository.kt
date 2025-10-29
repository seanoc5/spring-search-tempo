package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.SpringRole
import org.springframework.data.jpa.repository.JpaRepository


interface SpringRoleRepository : JpaRepository<SpringRole, Long> {

    fun findFirstBySpringUserId(id: Long): SpringRole?

    fun existsByLabel(label: String?): Boolean

}
