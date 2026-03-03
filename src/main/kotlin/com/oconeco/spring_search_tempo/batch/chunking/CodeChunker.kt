package com.oconeco.spring_search_tempo.batch.chunking

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Chunking strategy for source code files.
 *
 * Splits code at logical boundaries:
 * - Function/method definitions
 * - Class definitions
 * - Top-level declarations
 *
 * Currently uses heuristic patterns. Future enhancement could use
 * tree-sitter or language-specific parsers for precise AST-based splitting.
 *
 * Supported languages (heuristic-based):
 * - Kotlin, Java, Scala (JVM family)
 * - JavaScript, TypeScript
 * - Python
 * - Go, Rust
 * - C, C++
 */
@Component
class CodeChunker : ChunkingStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(CodeChunker::class.java)

        // Maximum chunk length before splitting further
        private const val MAX_CHUNK_LENGTH = 10000

        // Minimum chunk length to keep
        private const val MIN_CHUNK_LENGTH = 50

        // File extensions this chunker handles
        private val SUPPORTED_EXTENSIONS = setOf(
            // JVM
            "kt", "kts", "java", "scala", "groovy",
            // JavaScript/TypeScript
            "js", "jsx", "ts", "tsx", "mjs", "cjs",
            // Python
            "py", "pyw",
            // Systems
            "go", "rs", "c", "cpp", "cc", "cxx", "h", "hpp",
            // Scripting
            "rb", "php", "pl", "pm",
            // Shell
            "sh", "bash", "zsh",
            // Config as code
            "gradle"
        )

        // Content types for code
        private val SUPPORTED_TYPES = setOf(
            "text/x-kotlin",
            "text/x-java-source",
            "text/x-java",
            "text/javascript",
            "application/javascript",
            "text/typescript",
            "text/x-python",
            "text/x-go",
            "text/x-rust",
            "text/x-c",
            "text/x-c++",
            "text/x-scala"
        )

        // Pattern for function/method definitions (language-agnostic heuristic)
        // Matches: fun/function/def/func followed by name
        private val FUNCTION_DEF_PATTERN = Regex(
            """(?m)^[ \t]*((?:public|private|protected|internal|override|suspend|inline|static|async|export|default)\s+)*(?:fun|function|def|func|fn)\s+\w+""",
            setOf(RegexOption.MULTILINE)
        )

        // Pattern for class/interface/object definitions
        private val CLASS_DEF_PATTERN = Regex(
            """(?m)^[ \t]*((?:public|private|protected|internal|abstract|sealed|data|open|final|export|default)\s+)*(?:class|interface|object|struct|enum|trait|type)\s+\w+""",
            setOf(RegexOption.MULTILINE)
        )

        // Common code section markers
        private val SECTION_MARKERS = listOf(
            Regex("""(?m)^[ \t]*(?://|#|/\*+)\s*={3,}"""),  // Comment dividers
            Regex("""(?m)^[ \t]*(?://|#)\s*-{3,}"""),       // Dashed dividers
            Regex("""(?m)^[ \t]*(?://|#)\s*(?:MARK|TODO|FIXME|SECTION|REGION):""")  // Section markers
        )
    }

    override val name: String = "code"

    // Higher priority than paragraph/sentence for code
    override val priority: Int = 20

    override fun supports(contentType: String?): Boolean {
        return contentType?.lowercase() in SUPPORTED_TYPES
    }

    /**
     * Check if this chunker should be used based on file extension.
     */
    fun supportsByExtension(extension: String?): Boolean {
        return extension?.lowercase() in SUPPORTED_EXTENSIONS
    }

    override fun chunk(text: String, metadata: ChunkMetadata): List<Chunk> {
        if (text.isBlank()) {
            return emptyList()
        }

        val language = detectLanguage(metadata)

        // Find all potential split points
        val splitPoints = findSplitPoints(text)

        if (splitPoints.isEmpty()) {
            // No good split points - fall back to line-based chunking
            return chunkByLines(text, metadata.fileId, language)
        }

        return createChunksAtSplitPoints(text, splitPoints, language)
    }

    /**
     * Finds positions where the code can be logically split.
     */
    private fun findSplitPoints(text: String): List<Int> {
        val points = mutableSetOf<Int>()

        // Function definitions
        FUNCTION_DEF_PATTERN.findAll(text).forEach { match ->
            points.add(match.range.first)
        }

        // Class definitions
        CLASS_DEF_PATTERN.findAll(text).forEach { match ->
            points.add(match.range.first)
        }

        // Section markers
        SECTION_MARKERS.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                points.add(match.range.first)
            }
        }

        return points.sorted()
    }

    /**
     * Creates chunks at the identified split points.
     */
    private fun createChunksAtSplitPoints(
        text: String,
        splitPoints: List<Int>,
        language: String?
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var chunkNumber = 0

        for (i in splitPoints.indices) {
            val start = splitPoints[i]
            val end = if (i + 1 < splitPoints.size) splitPoints[i + 1] else text.length

            val chunkText = text.substring(start, end).trim()

            if (chunkText.length < MIN_CHUNK_LENGTH) {
                continue
            }

            // If chunk is too long, split it further
            if (chunkText.length > MAX_CHUNK_LENGTH) {
                val subChunks = chunkByLines(chunkText, 0, language, chunkNumber, start)
                chunks.addAll(subChunks)
                chunkNumber += subChunks.size
            } else {
                val chunkType = detectChunkType(chunkText)
                chunks.add(
                    Chunk(
                        text = chunkText,
                        chunkNumber = chunkNumber,
                        startPosition = start.toLong(),
                        endPosition = end.toLong(),
                        chunkType = chunkType,
                        language = language
                    )
                )
                chunkNumber++
            }
        }

        // Handle any content before the first split point
        if (splitPoints.isNotEmpty() && splitPoints.first() > MIN_CHUNK_LENGTH) {
            val preamble = text.substring(0, splitPoints.first()).trim()
            if (preamble.length >= MIN_CHUNK_LENGTH) {
                chunks.add(
                    0,
                    Chunk(
                        text = preamble,
                        chunkNumber = 0,
                        startPosition = 0,
                        endPosition = splitPoints.first().toLong(),
                        chunkType = "CodePreamble",
                        language = language
                    )
                )
                // Re-number subsequent chunks
                chunks.forEachIndexed { index, chunk ->
                    if (index > 0) {
                        chunks[index] = chunk.copy(chunkNumber = index)
                    }
                }
            }
        }

        return chunks
    }

    /**
     * Falls back to line-based chunking when no logical boundaries found.
     */
    private fun chunkByLines(
        text: String,
        fileId: Long,
        language: String?,
        startChunkNumber: Int = 0,
        startOffset: Int = 0
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val lines = text.lines()

        var currentChunk = StringBuilder()
        var chunkStart = startOffset
        var chunkNum = startChunkNumber
        var position = startOffset

        for (line in lines) {
            // If adding this line would exceed max, save current chunk
            if (currentChunk.length + line.length + 1 > MAX_CHUNK_LENGTH && currentChunk.isNotEmpty()) {
                val chunkText = currentChunk.toString().trim()
                if (chunkText.length >= MIN_CHUNK_LENGTH) {
                    chunks.add(
                        Chunk(
                            text = chunkText,
                            chunkNumber = chunkNum,
                            startPosition = chunkStart.toLong(),
                            endPosition = position.toLong(),
                            chunkType = "CodeBlock",
                            language = language
                        )
                    )
                    chunkNum++
                }
                chunkStart = position
                currentChunk = StringBuilder()
            }

            currentChunk.appendLine(line)
            position += line.length + 1 // +1 for newline
        }

        // Add remaining content
        val remaining = currentChunk.toString().trim()
        if (remaining.length >= MIN_CHUNK_LENGTH) {
            chunks.add(
                Chunk(
                    text = remaining,
                    chunkNumber = chunkNum,
                    startPosition = chunkStart.toLong(),
                    endPosition = (startOffset + text.length).toLong(),
                    chunkType = "CodeBlock",
                    language = language
                )
            )
        }

        return chunks
    }

    /**
     * Detects the programming language from metadata.
     */
    private fun detectLanguage(metadata: ChunkMetadata): String? {
        return when (metadata.fileExtension?.lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "scala" -> "scala"
            "groovy", "gradle" -> "groovy"
            "js", "jsx", "mjs", "cjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py", "pyw" -> "python"
            "go" -> "go"
            "rs" -> "rust"
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp" -> "cpp"
            "rb" -> "ruby"
            "php" -> "php"
            "sh", "bash", "zsh" -> "shell"
            else -> null
        }
    }

    /**
     * Detects the type of code chunk based on its content.
     */
    private fun detectChunkType(text: String): String {
        val firstLine = text.lines().firstOrNull()?.trim() ?: return "CodeBlock"

        return when {
            CLASS_DEF_PATTERN.containsMatchIn(firstLine) -> "Class"
            FUNCTION_DEF_PATTERN.containsMatchIn(firstLine) -> "Function"
            firstLine.startsWith("import ") || firstLine.startsWith("from ") -> "Imports"
            firstLine.startsWith("package ") -> "Package"
            else -> "CodeBlock"
        }
    }
}
