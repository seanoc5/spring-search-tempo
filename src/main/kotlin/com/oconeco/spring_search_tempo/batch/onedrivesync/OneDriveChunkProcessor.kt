package com.oconeco.spring_search_tempo.batch.onedrivesync

import com.oconeco.spring_search_tempo.base.model.ContentChunkDTO
import com.oconeco.spring_search_tempo.base.model.OneDriveItemDTO
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor


/**
 * Pass 3 Processor: Splits OneDrive item bodyText into sentence-level ContentChunks.
 *
 * Same sentence-splitting logic as EmailChunkProcessor, but creates chunks linked
 * to OneDriveItem instead of EmailMessage.
 */
class OneDriveChunkProcessor : ItemProcessor<OneDriveItemDTO, List<ContentChunkDTO>> {

    companion object {
        private val log = LoggerFactory.getLogger(OneDriveChunkProcessor::class.java)

        private val SENTENCE_BOUNDARY_REGEX = Regex(
            """(?<=[.!?])\s+(?=[A-Z])|(?<=[.!?])$""",
            RegexOption.MULTILINE
        )

        private const val MAX_CHUNK_LENGTH = 5000
        private const val MIN_CHUNK_LENGTH = 10
    }

    private var totalChunksCreated = 0

    override fun process(item: OneDriveItemDTO): List<ContentChunkDTO>? {
        val bodyText = item.bodyText
        if (bodyText.isNullOrBlank()) {
            log.debug("Skipping OneDrive item {} - no bodyText", item.uri)
            return null
        }

        try {
            val chunks = splitIntoSentences(bodyText, item.id!!)

            if (chunks.isEmpty()) {
                log.debug("No chunks created for OneDrive item {} (text length: {})", item.uri, bodyText.length)
                return null
            }

            totalChunksCreated += chunks.size

            if (totalChunksCreated % 100 == 0) {
                log.info("OneDriveChunkProcessor progress: {} chunks created", totalChunksCreated)
            }

            log.debug("Created {} chunks for OneDrive item {}", chunks.size, item.uri)
            return chunks

        } catch (e: Exception) {
            log.error("Error chunking OneDrive item {}: {}", item.uri, e.message, e)
            return null
        }
    }

    private fun splitIntoSentences(text: String, oneDriveItemId: Long): List<ContentChunkDTO> {
        val chunks = mutableListOf<ContentChunkDTO>()
        val sentences = text.split(SENTENCE_BOUNDARY_REGEX)

        var position = 0
        var chunkNumber = 0

        for (sentence in sentences) {
            val trimmed = sentence.trim()

            if (trimmed.length < MIN_CHUNK_LENGTH) {
                position += sentence.length
                continue
            }

            if (trimmed.length > MAX_CHUNK_LENGTH) {
                val subChunks = splitLongSentence(trimmed, oneDriveItemId, position, chunkNumber)
                chunks.addAll(subChunks)
                chunkNumber += subChunks.size
            } else {
                chunks.add(createChunk(
                    text = trimmed,
                    oneDriveItemId = oneDriveItemId,
                    chunkNumber = chunkNumber,
                    startPosition = position.toLong(),
                    endPosition = (position + trimmed.length).toLong()
                ))
                chunkNumber++
            }

            position += sentence.length
        }

        return chunks
    }

    private fun splitLongSentence(
        text: String,
        oneDriveItemId: Long,
        startOffset: Int,
        startChunkNumber: Int
    ): List<ContentChunkDTO> {
        val chunks = mutableListOf<ContentChunkDTO>()

        // Try paragraph breaks first
        val paragraphs = text.split(Regex("\n\n+"))

        if (paragraphs.size > 1) {
            var offset = startOffset
            var chunkNum = startChunkNumber

            for (para in paragraphs) {
                val trimmed = para.trim()
                if (trimmed.length >= MIN_CHUNK_LENGTH) {
                    chunks.add(createChunk(
                        text = trimmed,
                        oneDriveItemId = oneDriveItemId,
                        chunkNumber = chunkNum,
                        startPosition = offset.toLong(),
                        endPosition = (offset + trimmed.length).toLong(),
                        chunkType = "Paragraph"
                    ))
                    chunkNum++
                }
                offset += para.length + 2
            }
        } else {
            var offset = startOffset
            var chunkNum = startChunkNumber
            var remaining = text

            while (remaining.length > MAX_CHUNK_LENGTH) {
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

                chunks.add(createChunk(
                    text = remaining.substring(0, breakPoint).trim(),
                    oneDriveItemId = oneDriveItemId,
                    chunkNumber = chunkNum,
                    startPosition = offset.toLong(),
                    endPosition = (offset + breakPoint).toLong(),
                    chunkType = "LongSentence"
                ))

                offset += breakPoint
                remaining = remaining.substring(breakPoint)
                chunkNum++
            }

            if (remaining.trim().length >= MIN_CHUNK_LENGTH) {
                chunks.add(createChunk(
                    text = remaining.trim(),
                    oneDriveItemId = oneDriveItemId,
                    chunkNumber = chunkNum,
                    startPosition = offset.toLong(),
                    endPosition = (offset + remaining.length).toLong(),
                    chunkType = "LongSentence"
                ))
            }
        }

        return chunks
    }

    private fun createChunk(
        text: String,
        oneDriveItemId: Long,
        chunkNumber: Int,
        startPosition: Long,
        endPosition: Long,
        chunkType: String = "Sentence"
    ): ContentChunkDTO {
        return ContentChunkDTO().apply {
            this.text = text
            this.oneDriveItem = oneDriveItemId  // Link to OneDriveItem
            this.concept = null
            this.emailMessage = null
            this.chunkNumber = chunkNumber
            this.startPosition = startPosition
            this.endPosition = endPosition
            this.textLength = text.length.toLong()
            this.chunkType = chunkType
            this.status = "CHUNKED"
        }
    }
}
