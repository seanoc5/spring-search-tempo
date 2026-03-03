package com.oconeco.spring_search_tempo.web.model

data class CrawlConfigRowMetrics(
    val folderCount: Long = 0,
    val fileCount: Long = 0,
    val totalFileSize: Long = 0
)

data class FolderRowMetrics(
    val directFolderCount: Long = 0,
    val recursiveFolderCount: Long = 0,
    val directFileCount: Long = 0,
    val recursiveFileCount: Long = 0,
    val totalFileSize: Long = 0
)
