package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.ConceptHierarchy
import org.springframework.data.jpa.repository.JpaRepository

interface ConceptHierarchyRepository : JpaRepository<ConceptHierarchy, Long> {

    fun existsByCode(code: String): Boolean

    fun findByCode(code: String): ConceptHierarchy?
}
