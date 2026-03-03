package com.oconeco.remotecrawler.model

/**
 * Analysis status levels for files and folders.
 * Determines how deeply a file/folder will be processed.
 */
enum class AnalysisStatus {
    /** Skip - persist metadata only, no text extraction or analysis */
    SKIP,
    /** Locate - metadata indexed for path-based search */
    LOCATE,
    /** Index - full text extraction and FTS indexing */
    INDEX,
    /** Analyze - Index + NLP (entities, sentiment) */
    ANALYZE,
    /** Semantic - Analyze + vector embeddings for semantic search */
    SEMANTIC
}
