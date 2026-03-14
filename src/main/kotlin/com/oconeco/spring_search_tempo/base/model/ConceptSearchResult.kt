package com.oconeco.spring_search_tempo.base.model

data class ConceptSearchResult(
    val id: Long,
    val hierarchyCode: String,
    val hierarchyLabel: String,
    val externalKey: String,
    val label: String,
    val description: String?,
    val address: String?,
    val path: String?
)
