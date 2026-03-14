package com.oconeco.spring_search_tempo.base.model

import java.time.OffsetDateTime

data class ConceptHierarchySummary(
    val code: String,
    val label: String,
    val description: String?,
    val activeNodeCount: Long,
    val rootNodeCount: Long,
    val lastImportedAt: OffsetDateTime?
)
