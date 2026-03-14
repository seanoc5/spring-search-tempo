package com.oconeco.spring_search_tempo.base

import com.oconeco.spring_search_tempo.base.model.ConceptHierarchySummary
import com.oconeco.spring_search_tempo.base.model.OconecoImportResult
import java.io.InputStream

interface ConceptHierarchyService {

    fun getHierarchySummary(code: String): ConceptHierarchySummary

    fun importOconecoHierarchy(inputStream: InputStream, originalFilename: String?): OconecoImportResult
}
