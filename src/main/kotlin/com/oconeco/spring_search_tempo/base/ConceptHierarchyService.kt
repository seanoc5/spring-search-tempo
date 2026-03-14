package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.ConceptHierarchySummary
import com.oconeco.spring_search_tempo.base.model.ConceptHierarchyOption
import com.oconeco.spring_search_tempo.base.model.ConceptNodeDetail
import com.oconeco.spring_search_tempo.base.model.ConceptNodeSummary
import com.oconeco.spring_search_tempo.base.model.ConceptSearchResult
import com.oconeco.spring_search_tempo.base.model.OconecoImportResult
import java.io.InputStream

interface ConceptHierarchyService {

    fun listHierarchies(): List<ConceptHierarchyOption>

    fun getHierarchySummary(code: String): ConceptHierarchySummary

    fun getRootNodes(hierarchyCode: String): List<ConceptNodeSummary>

    fun getNodeDetail(nodeId: Long): ConceptNodeDetail

    fun search(query: String, hierarchyCode: String? = null, limit: Int = 100): List<ConceptSearchResult>

    fun importOconecoHierarchy(inputStream: InputStream, originalFilename: String?): OconecoImportResult
}
