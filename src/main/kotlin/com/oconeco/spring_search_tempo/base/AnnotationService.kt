package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.AnnotationDTO
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable


interface AnnotationService {

    fun findAll(filter: String?, pageable: Pageable): Page<AnnotationDTO>

    fun `get`(id: Long): AnnotationDTO

    fun create(annotationDTO: AnnotationDTO): Long

    fun update(id: Long, annotationDTO: AnnotationDTO)

    fun delete(id: Long)

    fun labelExists(label: String?): Boolean

}
