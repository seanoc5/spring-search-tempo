package com.oconeco.spring_search_tempo.base.model

data class OconecoImportResult(
    val hierarchyCode: String,
    val importedRows: Int,
    val createdNodes: Int,
    val updatedNodes: Int,
    val deactivatedNodes: Int,
    val rootNodes: Int,
    val detectedColumns: List<String>
)
