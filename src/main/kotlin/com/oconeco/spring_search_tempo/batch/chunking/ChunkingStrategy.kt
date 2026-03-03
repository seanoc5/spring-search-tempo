package com.oconeco.spring_search_tempo.batch.chunking

/**
 * Strategy interface for splitting text content into chunks.
 *
 * Different content types benefit from different chunking approaches:
 * - Prose documents: sentence-based chunking
 * - Technical docs: paragraph-based chunking
 * - Source code: method/function boundary chunking
 * - Future: semantic chunking based on topic boundaries
 *
 * Implementations should be stateless and thread-safe.
 */
interface ChunkingStrategy {

    /**
     * The name of this chunking strategy (for logging and configuration).
     */
    val name: String

    /**
     * Priority for strategy selection. Higher priority strategies are checked first.
     * Use for content-type specific strategies that should override defaults.
     */
    val priority: Int get() = 0

    /**
     * Determines if this strategy supports the given content type.
     *
     * @param contentType MIME type (e.g., "text/plain", "application/pdf")
     * @return true if this strategy can handle the content type
     */
    fun supports(contentType: String?): Boolean

    /**
     * Splits text into chunks.
     *
     * @param text The text content to chunk
     * @param metadata Context about the source (file info, content type, etc.)
     * @return List of chunks with position and type information
     */
    fun chunk(text: String, metadata: ChunkMetadata): List<Chunk>
}

/**
 * Metadata about the content being chunked.
 * Provides context that strategies may use to adjust their behavior.
 */
data class ChunkMetadata(
    /** ID of the parent FSFile, used to link chunks back to their source */
    val fileId: Long,
    /** MIME type of the source content */
    val contentType: String? = null,
    /** File extension (without dot), e.g., "kt", "java", "md" */
    val fileExtension: String? = null,
    /** URI/path of the source file for logging */
    val uri: String? = null,
    /** Original file size in bytes */
    val fileSize: Long? = null
)

/**
 * A chunk of text with position and type information.
 */
data class Chunk(
    /** The chunk text content */
    val text: String,
    /** Sequential chunk number within the parent file (0-based) */
    val chunkNumber: Int,
    /** Byte offset of chunk start in original text */
    val startPosition: Long,
    /** Byte offset of chunk end in original text */
    val endPosition: Long,
    /** Type of chunk (Sentence, Paragraph, Method, etc.) */
    val chunkType: String,
    /** Optional: language/format hint for code chunks */
    val language: String? = null
)
