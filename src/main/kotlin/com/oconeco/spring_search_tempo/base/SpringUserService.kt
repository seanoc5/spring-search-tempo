package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.SpringUserDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface SpringUserService {

    fun findAll(filter: String?, pageable: Pageable): Page<SpringUserDTO>

    fun `get`(id: Long): SpringUserDTO

    fun findByLabel(label: String): SpringUserDTO?

    fun create(springUserDTO: SpringUserDTO): Long

    fun update(id: Long, springUserDTO: SpringUserDTO)

    fun delete(id: Long)

    fun labelExists(label: String?): Boolean

    fun getSpringUserValues(): Map<Long, String>

}
