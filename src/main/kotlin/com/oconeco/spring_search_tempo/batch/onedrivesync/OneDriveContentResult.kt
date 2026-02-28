package com.oconeco.spring_search_tempo.batch.onedrivesync


/**
 * Result of content download and text extraction for a OneDrive item.
 */
data class OneDriveContentResult(
    val itemId: Long,
    val bodyText: String? = null,
    val bodySize: Long? = null,
    val contentType: String? = null,
    val author: String? = null,
    val title: String? = null,
    val pageCount: Int? = null,
    val failed: Boolean = false,
    val errorMessage: String? = null
)
