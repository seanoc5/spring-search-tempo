package com.oconeco.spring_search_tempo.batch.emailcrawl

import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.model.EmailMessageDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor


/**
 * ItemProcessor that splits EmailMessageDTO bodyText into sentence-level ContentChunks.
 *
 * Similar to ChunkProcessor for FSFiles, but creates chunks linked to EmailMessage
 * instead of FSFile (concept).
 *
 * Processing steps:
 * 1. Split bodyText into sentences using regex patterns
 * 2. Create ContentChunkDTO for each sentence
 * 3. Track start/end positions within the source text
 * 4. Link chunks to their parent EmailMessage
 * 5. Set chunk metadata (type, number, length)
 */
class EmailChunkProcessor : ItemProcessor<EmailMessageDTO, List<ContentChunkDTO>> {

    companion object {
        private val log = LoggerFactory.getLogger(EmailChunkProcessor::class.java)

        // Sentence boundary regex - matches . ! ? followed by whitespace or end of string
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

    override fun process(item: EmailMessageDTO): List<ContentChunkDTO>? {
        val bodyText = item.bodyText
        if (bodyText.isNullOrBlank()) {
            log.debug("Skipping email {} - no bodyText", item.uri)
            return null
        }

        try {
            val chunks = splitIntoSentences(bodyText, item.id!!)

            if (chunks.isEmpty()) {
                log.debug("No chunks created for email {} (text length: {})", item.uri, bodyText.length)
                return null
            }

            totalChunksCreated += chunks.size

            if (totalChunksCreated % 100 == 0) {
                log.info("EmailChunkProcessor progress: {} chunks created", totalChunksCreated)
            }

            log.debug("Created {} chunks for email {}", chunks.size, item.uri)
            return chunks

        } catch (e: Exception) {
            log.error("Error processing email {}: {}", item.uri, e.message, e)
            return null
        }
    }

    /**
     * Splits text into sentence-based chunks.
     *
     * @param text The text to split
     * @param emailMessageId The EmailMessage ID to link chunks to
     * @return List of ContentChunkDTO representing sentences
     */
    private fun splitIntoSentences(text: String, emailMessageId: Long): List<ContentChunkDTO> {
        val chunks = mutableListOf<ContentChunkDTO>()

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
                val subChunks = splitLongSentence(trimmed, emailMessageId, position, chunkNumber)
                chunks.addAll(subChunks)
                chunkNumber += subChunks.size
            } else {
                // Create chunk for this sentence
                val chunk = createChunk(
                    text = trimmed,
                    emailMessageId = emailMessageId,
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
        emailMessageId: Long,
        startOffset: Int,
        startChunkNumber: Int
    ): List<ContentChunkDTO> {
        val chunks = mutableListOf<ContentChunkDTO>()

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
                        emailMessageId = emailMessageId,
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
                    emailMessageId = emailMessageId,
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
                    emailMessageId = emailMessageId,
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
     * Creates a ContentChunkDTO linked to an EmailMessage.
     */
    private fun createChunk(
        text: String,
        emailMessageId: Long,
        chunkNumber: Int,
        startPosition: Long,
        endPosition: Long,
        chunkType: String = "Sentence"
    ): ContentChunkDTO {
        return ContentChunkDTO().apply {
            this.text = text
            this.emailMessage = emailMessageId  // Link to email instead of FSFile
            this.concept = null  // Not linked to FSFile
            this.chunkNumber = chunkNumber
            this.startPosition = startPosition
            this.endPosition = endPosition
            this.textLength = text.length.toLong()
            this.chunkType = chunkType
            this.status = "CHUNKED"
        }
    }
}
