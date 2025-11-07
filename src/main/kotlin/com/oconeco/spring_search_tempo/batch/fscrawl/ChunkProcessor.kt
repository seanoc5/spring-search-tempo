package com.oconeco.spring_search_tempo.batch.fscrawl

import com.oconeco.spring_search_tempo.base.domain.FSFile
import com.oconeco.spring_search_tempo.base.model.ContentChunksDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor

/**
 * ItemProcessor that splits FSFile bodyText into sentence-level ContentChunks.
 *
 * For Phase 1, uses simple regex-based sentence splitting. Phase 2 will enhance
 * this with Stanford CoreNLP for linguistic analysis.
 *
 * Processing steps:
 * 1. Split bodyText into sentences using regex patterns
 * 2. Create ContentChunksDTO for each sentence
 * 3. Track start/end positions within the source text
 * 4. Link chunks to their parent FSFile (concept)
 * 5. Set chunk metadata (type, number, length)
 *
 * @return List of ContentChunksDTO, one per sentence (or null if no bodyText)
 */
class ChunkProcessor : ItemProcessor<FSFile, List<ContentChunksDTO>> {

    companion object {
        private val log = LoggerFactory.getLogger(ChunkProcessor::class.java)

        // Sentence boundary regex - matches . ! ? followed by whitespace or end of string
        // Handles common abbreviations to avoid false splits
        private val SENTENCE_BOUNDARY_REGEX = Regex(
            """(?<=[.!?])\s+(?=[A-Z])|(?<=[.!?])$""",
            RegexOption.MULTILINE
        )

        // Maximum chunk length - very long "sentences" will be split
        private const val MAX_CHUNK_LENGTH = 5000

        // Minimum chunk length - very short sentences are skipped
        private const val MIN_CHUNK_LENGTH = 10
    }

    private var totalChunksCreated = 0

    override fun process(item: FSFile): List<ContentChunksDTO>? {
        val bodyText = item.bodyText
        if (bodyText.isNullOrBlank()) {
            log.debug("Skipping file {} - no bodyText", item.uri)
            return null
        }

        try {
            val chunks = splitIntoSentences(bodyText, item.id!!)

            if (chunks.isEmpty()) {
                log.debug("No chunks created for file {} (text length: {})", item.uri, bodyText.length)
                return null
            }

            totalChunksCreated += chunks.size

            if (totalChunksCreated % 100 == 0) {
                log.info("ChunkProcessor progress: {} chunks created", totalChunksCreated)
            }

            log.debug("Created {} chunks for file {}", chunks.size, item.uri)
            return chunks

        } catch (e: Exception) {
            log.error("Error processing file {}: {}", item.uri, e.message, e)
            return null
        }
    }

    /**
     * Splits text into sentence-based chunks.
     *
     * @param text The text to split
     * @param fileId The FSFile ID to link chunks to
     * @return List of ContentChunksDTO representing sentences
     */
    private fun splitIntoSentences(text: String, fileId: Long): List<ContentChunksDTO> {
        val chunks = mutableListOf<ContentChunksDTO>()

        // Split into sentences
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
                val subChunks = splitLongSentence(trimmed, fileId, position, chunkNumber)
                chunks.addAll(subChunks)
                chunkNumber += subChunks.size
            } else {
                // Create chunk for this sentence
                val chunk = createChunk(
                    text = trimmed,
                    fileId = fileId,
                    chunkNumber = chunkNumber,
                    startPosition = position.toLong(),
                    endPosition = (position + trimmed.length).toLong()
                )
                chunks.add(chunk)
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
        fileId: Long,
        startOffset: Int,
        startChunkNumber: Int
    ): List<ContentChunksDTO> {
        val chunks = mutableListOf<ContentChunksDTO>()

        // Try to split at paragraph breaks first
        val paragraphs = text.split(Regex("\n\n+"))

        if (paragraphs.size > 1) {
            // Split into paragraphs
            var offset = startOffset
            var chunkNum = startChunkNumber

            for (para in paragraphs) {
                val trimmed = para.trim()
                if (trimmed.length >= MIN_CHUNK_LENGTH) {
                    val chunk = createChunk(
                        text = trimmed,
                        fileId = fileId,
                        chunkNumber = chunkNum,
                        startPosition = offset.toLong(),
                        endPosition = (offset + trimmed.length).toLong(),
                        chunkType = "Paragraph"
                    )
                    chunks.add(chunk)
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

                val chunk = createChunk(
                    text = remaining.substring(0, breakPoint).trim(),
                    fileId = fileId,
                    chunkNumber = chunkNum,
                    startPosition = offset.toLong(),
                    endPosition = (offset + breakPoint).toLong(),
                    chunkType = "LongSentence"
                )
                chunks.add(chunk)

                offset += breakPoint
                remaining = remaining.substring(breakPoint)
                chunkNum++
            }

            // Add the remaining text if it's long enough
            if (remaining.trim().length >= MIN_CHUNK_LENGTH) {
                val chunk = createChunk(
                    text = remaining.trim(),
                    fileId = fileId,
                    chunkNumber = chunkNum,
                    startPosition = offset.toLong(),
                    endPosition = (offset + remaining.length).toLong(),
                    chunkType = "LongSentence"
                )
                chunks.add(chunk)
            }
        }

        return chunks
    }

    /**
     * Creates a ContentChunksDTO with the specified parameters.
     */
    private fun createChunk(
        text: String,
        fileId: Long,
        chunkNumber: Int,
        startPosition: Long,
        endPosition: Long,
        chunkType: String = "Sentence"
    ): ContentChunksDTO {
        return ContentChunksDTO().apply {
            this.text = text
            this.concept = fileId
            this.chunkNumber = chunkNumber
            this.startPosition = startPosition
            this.endPosition = endPosition
            this.textLength = text.length.toLong()
            this.chunkType = chunkType
            this.status = "CHUNKED"
        }
    }
}
