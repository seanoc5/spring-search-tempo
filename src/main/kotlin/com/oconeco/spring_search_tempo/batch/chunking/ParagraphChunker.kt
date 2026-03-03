package com.oconeco.spring_search_tempo.batch.chunking

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Chunking strategy that splits text at paragraph boundaries.
 *
 * Best for:
 * - Technical documentation (markdown, HTML)
 * - Structured text with clear paragraph breaks
 * - Content where semantic meaning aligns with visual paragraphs
 *
 * Paragraph detection:
 * - Blank lines (two or more newlines)
 * - HTML paragraph tags (for extracted HTML content)
 */
@Component
class ParagraphChunker : ChunkingStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(ParagraphChunker::class.java)

        // Paragraph break patterns
        private val PARAGRAPH_BREAK_REGEX = Regex("\n\\s*\n")

        // Maximum paragraph length before sub-splitting
        private const val MAX_PARAGRAPH_LENGTH = 8000

        // Minimum paragraph length to keep
        private const val MIN_PARAGRAPH_LENGTH = 20

        // Content types this chunker handles
        private val SUPPORTED_TYPES = setOf(
            "text/markdown",
            "text/x-markdown",
            "text/html",
            "application/xhtml+xml"
        )

        // File extensions this chunker handles
        private val SUPPORTED_EXTENSIONS = setOf(
            "md",
            "markdown",
            "html",
            "htm",
            "xhtml"
        )
    }

    override val name: String = "paragraph"

    // Higher priority than sentence chunker for supported types
    override val priority: Int = 10

    override fun supports(contentType: String?): Boolean {
        return contentType?.lowercase() in SUPPORTED_TYPES
    }

    /**
     * Check if this chunker should be used based on file extension.
     * Called when content type doesn't match but extension might.
     */
    fun supportsByExtension(extension: String?): Boolean {
        return extension?.lowercase() in SUPPORTED_EXTENSIONS
    }

    override fun chunk(text: String, metadata: ChunkMetadata): List<Chunk> {
        if (text.isBlank()) {
            return emptyList()
        }

        val chunks = mutableListOf<Chunk>()
        val paragraphs = text.split(PARAGRAPH_BREAK_REGEX)

        var position = 0
        var chunkNumber = 0

        for (para in paragraphs) {
            val trimmed = para.trim()

            // Skip very short paragraphs
            if (trimmed.length < MIN_PARAGRAPH_LENGTH) {
                position += para.length + 2 // +2 for paragraph break
                continue
            }

            // Split very long paragraphs
            if (trimmed.length > MAX_PARAGRAPH_LENGTH) {
                val subChunks = splitLongParagraph(trimmed, position, chunkNumber)
                chunks.addAll(subChunks)
                chunkNumber += subChunks.size
            } else {
                chunks.add(
                    Chunk(
                        text = trimmed,
                        chunkNumber = chunkNumber,
                        startPosition = position.toLong(),
                        endPosition = (position + trimmed.length).toLong(),
                        chunkType = "Paragraph"
                    )
                )
                chunkNumber++
            }

            position += para.length + 2
        }

        return chunks
    }

    /**
     * Splits very long paragraphs at sentence or clause boundaries.
     */
    private fun splitLongParagraph(
        text: String,
        startOffset: Int,
        startChunkNumber: Int
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()

        // Try to split at sentence boundaries first
        val sentenceRegex = Regex("""(?<=[.!?])\s+(?=[A-Z])""")
        val sentences = text.split(sentenceRegex)

        var currentChunk = StringBuilder()
        var chunkStart = startOffset
        var chunkNum = startChunkNumber
        var position = startOffset

        for (sentence in sentences) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) continue

            // If adding this sentence would exceed max, save current chunk
            if (currentChunk.length + trimmed.length > MAX_PARAGRAPH_LENGTH && currentChunk.isNotEmpty()) {
                chunks.add(
                    Chunk(
                        text = currentChunk.toString().trim(),
                        chunkNumber = chunkNum,
                        startPosition = chunkStart.toLong(),
                        endPosition = position.toLong(),
                        chunkType = "ParagraphSection"
                    )
                )
                chunkNum++
                chunkStart = position
                currentChunk = StringBuilder()
            }

            if (currentChunk.isNotEmpty()) {
                currentChunk.append(" ")
            }
            currentChunk.append(trimmed)
            position += sentence.length
        }

        // Add remaining content
        if (currentChunk.isNotEmpty() && currentChunk.length >= MIN_PARAGRAPH_LENGTH) {
            chunks.add(
                Chunk(
                    text = currentChunk.toString().trim(),
                    chunkNumber = chunkNum,
                    startPosition = chunkStart.toLong(),
                    endPosition = (startOffset + text.length).toLong(),
                    chunkType = "ParagraphSection"
                )
            )
        }

        return chunks
    }
}
