package com.oconeco.spring_search_tempo.web.model

data class DashboardSystemInfo(
    val applicationName: String,
    val applicationVersion: String,
    val springBootVersion: String,
    val databaseName: String,
    val textExtraction: String,
    val statusLabel: String,
    val statusBadgeClass: String
)

