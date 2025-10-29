package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.Annotation
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository


interface AnnotationRepository : JpaRepository<Annotation, Long> {

    fun findAllById(id: Long?, pageable: Pageable): Page<Annotation>

    fun existsByLabel(label: String?): Boolean

}
