package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.ConceptNode
import org.springframework.data.jpa.repository.JpaRepository

interface ConceptNodeRepository : JpaRepository<ConceptNode, Long> {

    fun findByHierarchyCodeAndExternalKey(hierarchyCode: String, externalKey: String): ConceptNode?

    fun findAllByHierarchyCodeAndParentIsNullOrderByLabelAsc(hierarchyCode: String): List<ConceptNode>

    fun findAllByHierarchyId(hierarchyId: Long): List<ConceptNode>

    fun countByHierarchyCodeAndActiveTrue(hierarchyCode: String): Long

    fun countByHierarchyCodeAndParentIsNullAndActiveTrue(hierarchyCode: String): Long
}
