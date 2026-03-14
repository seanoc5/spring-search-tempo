package com.oconeco.spring_search_tempo.base.repos

import com.oconeco.spring_search_tempo.base.domain.ConceptNode
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ConceptNodeRepository : JpaRepository<ConceptNode, Long> {

    fun findByHierarchyCodeAndExternalKey(hierarchyCode: String, externalKey: String): ConceptNode?

    fun findAllByHierarchyCodeAndParentIsNullOrderByLabelAsc(hierarchyCode: String): List<ConceptNode>

    fun findAllByHierarchyCodeAndParentIsNullAndActiveTrueOrderByLabelAsc(hierarchyCode: String): List<ConceptNode>

    fun findAllByParentIdAndActiveTrueOrderByLabelAsc(parentId: Long): List<ConceptNode>

    fun findAllByHierarchyId(hierarchyId: Long): List<ConceptNode>

    fun countByHierarchyCodeAndActiveTrue(hierarchyCode: String): Long

    fun countByHierarchyCodeAndParentIsNullAndActiveTrue(hierarchyCode: String): Long

    @Query("""
        SELECT n FROM ConceptNode n
        JOIN FETCH n.hierarchy h
        LEFT JOIN FETCH n.parent p
        WHERE n.id = :id
    """)
    fun findWithHierarchyAndParentById(@Param("id") id: Long): ConceptNode?

    @Query("""
        SELECT n FROM ConceptNode n
        JOIN FETCH n.hierarchy h
        LEFT JOIN FETCH n.parent p
        WHERE n.active = true
          AND (
              LOWER(n.label) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(COALESCE(n.description, '')) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(COALESCE(n.address, '')) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(COALESCE(n.path, '')) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(COALESCE(n.externalKey, '')) LIKE LOWER(CONCAT('%', :query, '%'))
          )
        ORDER BY h.label ASC, n.path ASC, n.label ASC
    """)
    fun searchActive(@Param("query") query: String): List<ConceptNode>

    @Query("""
        SELECT n FROM ConceptNode n
        JOIN FETCH n.hierarchy h
        LEFT JOIN FETCH n.parent p
        WHERE n.active = true
          AND h.code = :hierarchyCode
          AND (
              LOWER(n.label) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(COALESCE(n.description, '')) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(COALESCE(n.address, '')) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(COALESCE(n.path, '')) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(COALESCE(n.externalKey, '')) LIKE LOWER(CONCAT('%', :query, '%'))
          )
        ORDER BY n.path ASC, n.label ASC
    """)
    fun searchActiveInHierarchy(
        @Param("hierarchyCode") hierarchyCode: String,
        @Param("query") query: String
    ): List<ConceptNode>
}
