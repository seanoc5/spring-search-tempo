package com.oconeco.spring_search_tempo.base.model

data class ConceptNodeDetail(
    val node: ConceptNodeSummary,
    val breadcrumbs: List<ConceptNodeSummary>,
    val children: List<ConceptNodeSummary>
)
