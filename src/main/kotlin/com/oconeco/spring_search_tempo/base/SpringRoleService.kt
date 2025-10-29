package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.SpringRoleDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface SpringRoleService {

    fun findAll(pageable: Pageable): Page<SpringRoleDTO>

    fun `get`(id: Long): SpringRoleDTO

    fun create(springRoleDTO: SpringRoleDTO): Long

    fun update(id: Long, springRoleDTO: SpringRoleDTO)

    fun delete(id: Long)

    fun labelExists(label: String?): Boolean

}
