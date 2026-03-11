package com.oconeco.spring_search_tempo.base.domain

/**
 * Detected folder type based on file sample analysis.
 *
 * Used to improve discovery classification suggestions by analyzing
 * the types of files present in a folder.
 */
enum class DetectedFolderType {
    /** Source code files: .kt, .java, .py, .ts, .js, .go, etc. */
    SOURCE_CODE,

    /** Documentation files: .md, .txt, .rst, .adoc */
    DOCUMENTATION,

    /** Office documents: .docx, .xlsx, .pdf, .pptx */
    OFFICE_DOCS,

    /** Media files: .jpg, .png, .mp4, .mp3 */
    MEDIA,

    /** Data files: .json, .csv, .xml, .yaml */
    DATA,

    /** Configuration files: application.*, .env, *.conf */
    CONFIG,

    /** Build artifacts: .class, .o, .pyc, node_modules contents */
    BUILD_ARTIFACTS,

    /** Mixed content - no dominant type (confidence < 60%) */
    MIXED,

    /** Unknown - not enough samples to determine */
    UNKNOWN
}
