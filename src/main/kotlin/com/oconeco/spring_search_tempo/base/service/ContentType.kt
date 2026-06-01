package com.oconeco.spring_search_tempo.base.service

/**
 * Types of searchable content in the system.
 * Used for filtering search results by content type.
 */
enum class ContentType {
    /**
     * Files (FSFile entities) - documents, code, etc.
     */
    FILE,

    /**
     * Email messages (EmailMessage entities)
     */
    EMAIL,

    /**
     * OneDrive items (OneDriveItem entities) - files synced from OneDrive
     */
    ONEDRIVE,

    /**
     * Content chunks (ContentChunk entities) - sentences/paragraphs from files or emails
     */
    CHUNK,

    /**
     * Browser bookmarks (BrowserBookmark entities) - Firefox/other-browser bookmarks
     * with tag and folder facets.
     */
    BOOKMARK
}
