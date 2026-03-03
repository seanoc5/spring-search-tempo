package com.oconeco.spring_search_tempo.batch.chunking

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Default chunking strategy that splits text into sentences.
 *
 * Uses regex-based sentence detection with handling for:
 * - Standard sentence endings (. ! ?)
 * - Long sentences that need sub-splitting
 * - Paragraph-based fallback for very long segments
 *
 * This is the default strategy for most text content types.
 */
@Component
class SentenceChunker : ChunkingStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(SentenceChunker::class.java)

        // Sentence boundary regex - matches . ! ? followed by whitespace and uppercase
        private val SENTENCE_BOUNDARY_REGEX = Regex(
            """(?<=[.!?])\s+(?=[A-Z])|(?<=[.!?])$""",
            RegexOption.MULTILINE
        )

        // Maximum chunk length - very long "sentences" will be split
        private const val MAX_CHUNK_LENGTH = 5000

        // Minimum chunk length - very short sentences are skipped
        private const val MIN_CHUNK_LENGTH = 10
    }

    override val name: String = "sentence"

    // Default priority - other strategies can override by having higher priority
    override val priority: Int = 0

    override fun supports(contentType: String?): Boolean {
        // Default strategy - supports everything that isn't explicitly handled elsewhere
        return true
    }

    override fun chunk(text: String, metadata: ChunkMetadata): List<Chunk> {
        if (text.isBlank()) {
            return emptyList()
        }

        val chunks = mutableListOf<Chunk>()
        val sentences = text.split(SENTENCE_BOUNDARY_REGEX)

        var position = 0
        var chunkNumber = 0

        for (sentence in sentences) {
            val trimmed = sentence.trim()

            // Skip very short or empty sentences
            if (trimmed.length < MIN_CHUNK_LENGTH) {
                position += sentence.length
                continue
            }

            // Handle very long "sentences" by splitting further
            if (trimmed.length > MAX_CHUNK_LENGTH) {
                val subChunks = splitLongSentence(trimmed, position, chunkNumber)
                chunks.addAll(subChunks)
                chunkNumber += subChunks.size
            } else {
                chunks.add(
                    Chunk(
                        text = trimmed,
                        chunkNumber = chunkNumber,
                        startPosition = position.toLong(),
                        endPosition = (position + trimmed.length).toLong(),
                        chunkType = "Sentence"
                    )
                )
                chunkNumber++
            }

            position += sentence.length
        }

        return chunks
    }

    /**
     * Splits very long sentences into smaller chunks at paragraph or clause boundaries.
     */
    private fun splitLongSentence(
        text: String,
        startOffset: Int,
        startChunkNumber: Int
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()

        // Try to split at paragraph breaks first
        val paragraphs = text.split(Regex("\n\n+"))

        if (paragraphs.size > 1) {
            var offset = startOffset
            var chunkNum = startChunkNumber

            for (para in paragraphs) {
                val trimmed = para.trim()
                if (trimmed.length >= MIN_CHUNK_LENGTH) {
                    chunks.add(
                        Chunk(
                            text = trimmed,
                            chunkNumber = chunkNum,
                            startPosition = offset.toLong(),
                            endPosition = (offset + trimmed.length).toLong(),
                            chunkType = "Paragraph"
                        )
                    )
                    chunkNum++
                }
                offset += para.length + 2 // +2 for the paragraph breaks
            }
        } else {
            // No paragraph breaks - split at MAX_CHUNK_LENGTH boundaries
            var offset = startOffset
            var chunkNum = startChunkNumber
            var remaining = text

            while (remaining.length > MAX_CHUNK_LENGTH) {
                // Find a good break point (space, comma, semicolon) near MAX_CHUNK_LENGTH
                var breakPoint = MAX_CHUNK_LENGTH
                val searchStart = maxOf(0, MAX_CHUNK_LENGTH - 200)
                val searchEnd = minOf(remaining.length, MAX_CHUNK_LENGTH + 200)

                val breakChars = charArrayOf(' ', ',', ';', '\n')
                for (i in searchEnd - 1 downTo searchStart) {
                    if (remaining[i] in breakChars) {
                        breakPoint = i + 1
                        break
                    }
                }

                chunks.add(
                    Chunk(
                        text = remaining.substring(0, breakPoint).trim(),
                        chunkNumber = chunkNum,
                        startPosition = offset.toLong(),
                        endPosition = (offset + breakPoint).toLong(),
                        chunkType = "LongSentence"
                    )
                )

                offset += breakPoint
                remaining = remaining.substring(breakPoint)
                chunkNum++
            }

            // Add the remaining text if it's long enough
            if (remaining.trim().length >= MIN_CHUNK_LENGTH) {
                chunks.add(
                    Chunk(
                        text = remaining.trim(),
                        chunkNumber = chunkNum,
                        startPosition = offset.toLong(),
                        endPosition = (offset + remaining.length).toLong(),
                        chunkType = "LongSentence"
                    )
                )
            }
        }

        return chunks
    }
}
