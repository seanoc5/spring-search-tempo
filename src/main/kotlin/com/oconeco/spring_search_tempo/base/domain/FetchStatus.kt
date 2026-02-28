package com.oconeco.spring_search_tempo.base.domain

/**
 * Status of email message content fetching.
 *
 * Supports two-pass sync strategy:
 * - Pass 1: Fetch headers only (fast bulk operation)
 * - Pass 2: Fetch bodies (slower, can be parallelized)
 */
enum class FetchStatus {
    /**
     * Only envelope/headers have been fetched.
     * Message is searchable by subject/sender/date but body is not available.
     */
    HEADERS_ONLY,

    /**
     * Full message including body has been fetched.
     * Message is fully searchable and ready for chunking/NLP.
     */
    COMPLETE
}
