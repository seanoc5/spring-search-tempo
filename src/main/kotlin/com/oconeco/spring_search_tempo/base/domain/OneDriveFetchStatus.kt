package com.oconeco.spring_search_tempo.base.domain

/**
 * Status of OneDrive item content fetching.
 *
 * Supports two-pass sync strategy:
 * - Pass 1: Delta sync fetches metadata only (fast bulk operation via Graph delta API)
 * - Pass 2: Content download fetches file bodies (slower, uses Tika extraction)
 */
enum class OneDriveFetchStatus {
    /**
     * Only metadata has been synced from Graph delta API.
     * Item is searchable by name/path but body content is not available.
     */
    METADATA_ONLY,

    /**
     * Full content including body text has been downloaded and extracted.
     * Item is fully searchable and ready for chunking/NLP.
     */
    COMPLETE,

    /**
     * Content download or text extraction failed.
     * Will be retried on next sync.
     */
    DOWNLOAD_FAILED
}
