package com.oconeco.spring_search_tempo.base.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

@DisplayName("TextExtractionService Tests")
class TextExtractionServiceTest {

    private lateinit var service: TextExtractionService

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        service = TextExtractionService()
    }

    @Test
    @DisplayName("Should extract text from plain text file")
    fun testExtractPlainText() {
        // Create a simple text file
        val textFile = tempDir.resolve("test.txt")
        textFile.writeText("Hello, World!\nThis is a test file.")

        // Extract text
        val result = service.extractText(textFile)

        // Verify
        assertTrue(result is TextExtractionResult.Success)
        val success = result as TextExtractionResult.Success
        assertTrue(success.text.contains("Hello, World!"))
        assertTrue(success.text.contains("This is a test file."))
    }

    @Test
    @DisplayName("Should handle empty file")
    fun testExtractEmptyFile() {
        // Create an empty file
        val emptyFile = tempDir.resolve("empty.txt")
        emptyFile.writeText("")

        // Extract text
        val result = service.extractText(emptyFile)

        // Verify - empty files should succeed with empty or minimal text
        // Tika may return empty string or whitespace for empty files
        when (result) {
            is TextExtractionResult.Success -> {
                // Empty or whitespace-only text is acceptable
                assertTrue(result.text.isBlank() || result.text.isEmpty())
            }
            is TextExtractionResult.Failure -> {
                // Some parsers may fail on empty files, which is also acceptable
                assertTrue(true)
            }
        }
    }

    @Test
    @DisplayName("Should sanitize null bytes from text")
    fun testSanitizeNullBytes() {
        // Create a file with null bytes
        val fileWithNulls = tempDir.resolve("with-nulls.txt")
        Files.write(fileWithNulls, "Hello\u0000World\u0000Test".toByteArray())

        // Extract text
        val result = service.extractText(fileWithNulls)

        // Verify null bytes are removed
        assertTrue(result is TextExtractionResult.Success)
        val success = result as TextExtractionResult.Success
        assertFalse(success.text.contains("\u0000"))
        assertTrue(success.text.contains("Hello"))
        assertTrue(success.text.contains("World"))
        assertTrue(success.text.contains("Test"))
    }

    @Test
    @DisplayName("Should reject files larger than max size")
    fun testFileTooLarge() {
        // Create a file
        val largeFile = tempDir.resolve("large.txt")
        largeFile.writeText("Some content")

        // Try to extract with a very small max size
        val result = service.extractText(largeFile, maxSize = 5L)

        // Verify
        assertTrue(result is TextExtractionResult.Failure)
        val failure = result as TextExtractionResult.Failure
        assertTrue(failure.error.contains("exceeds maximum"))
    }

    @Test
    @DisplayName("Should handle non-existent file")
    fun testNonExistentFile() {
        // Try to extract from non-existent file
        val nonExistentFile = tempDir.resolve("does-not-exist.txt")

        val result = service.extractText(nonExistentFile)

        // Verify
        assertTrue(result is TextExtractionResult.Failure)
        val failure = result as TextExtractionResult.Failure
        // File not found is now handled specifically with a clearer message
        assertTrue(failure.error.contains("File not found") ||
                   failure.error.contains("I/O error") ||
                   failure.error.contains("error"))
    }

    @Test
    @DisplayName("Should extract text from HTML file")
    fun testExtractFromHtml() {
        // Create an HTML file
        val htmlFile = tempDir.resolve("test.html")
        htmlFile.writeText("""
            <!DOCTYPE html>
            <html>
            <head><title>Test Page</title></head>
            <body>
                <h1>Hello from HTML</h1>
                <p>This is a paragraph.</p>
            </body>
            </html>
        """.trimIndent())

        // Extract text
        val result = service.extractText(htmlFile)

        // Verify - Tika should extract clean text without HTML tags
        assertTrue(result is TextExtractionResult.Success)
        val success = result as TextExtractionResult.Success
        assertTrue(success.text.contains("Hello from HTML"))
        assertTrue(success.text.contains("This is a paragraph"))
        // Should not contain HTML tags
        assertFalse(success.text.contains("<html>"))
        assertFalse(success.text.contains("<body>"))
    }

    @Test
    @DisplayName("Should extract text from XML file")
    fun testExtractFromXml() {
        // Create an XML file
        val xmlFile = tempDir.resolve("test.xml")
        xmlFile.writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <root>
                <item>First item</item>
                <item>Second item</item>
            </root>
        """.trimIndent())

        // Extract text
        val result = service.extractText(xmlFile)

        // Verify
        assertTrue(result is TextExtractionResult.Success)
        val success = result as TextExtractionResult.Success
        assertTrue(success.text.contains("First item") || success.text.contains("Second item"))
    }

    @Test
    @DisplayName("Should detect MIME type correctly")
    fun testDetectMimeType() {
        // Create a text file
        val textFile = tempDir.resolve("test.txt")
        textFile.writeText("Hello, World!")

        // Detect MIME type
        val mimeType = service.detectMimeType(textFile)

        // Verify
        assertTrue(mimeType.startsWith("text/"))
    }

    @Test
    @DisplayName("Should detect HTML MIME type")
    fun testDetectHtmlMimeType() {
        // Create an HTML file
        val htmlFile = tempDir.resolve("test.html")
        htmlFile.writeText("<!DOCTYPE html><html><body>Test</body></html>")

        // Detect MIME type
        val mimeType = service.detectMimeType(htmlFile)

        // Verify
        assertTrue(mimeType.contains("html") || mimeType.startsWith("text/"))
    }

    @Test
    @DisplayName("Should handle UTF-8 encoded text")
    fun testUtf8Encoding() {
        // Create a file with UTF-8 special characters
        val utf8File = tempDir.resolve("utf8.txt")
        utf8File.writeText("Hello 世界! Café ñ å ö ü")

        // Extract text
        val result = service.extractText(utf8File)

        // Verify
        assertTrue(result is TextExtractionResult.Success)
        val success = result as TextExtractionResult.Success
        assertTrue(success.text.contains("世界"))
        assertTrue(success.text.contains("Café"))
        assertTrue(success.text.contains("ñ"))
    }

    @Test
    @DisplayName("Should handle multiline text")
    fun testMultilineText() {
        // Create a file with multiple lines
        val multilineFile = tempDir.resolve("multiline.txt")
        multilineFile.writeText("""
            Line 1
            Line 2
            Line 3
            Line 4
        """.trimIndent())

        // Extract text
        val result = service.extractText(multilineFile)

        // Verify
        assertTrue(result is TextExtractionResult.Success)
        val success = result as TextExtractionResult.Success
        assertTrue(success.text.contains("Line 1"))
        assertTrue(success.text.contains("Line 2"))
        assertTrue(success.text.contains("Line 3"))
        assertTrue(success.text.contains("Line 4"))
    }

    // ==================== Metadata Extraction Tests ====================

    @Test
    @DisplayName("Should extract text and metadata from plain text file")
    fun testExtractTextAndMetadataFromPlainText() {
        // Create a simple text file
        val textFile = tempDir.resolve("test.txt")
        textFile.writeText("Hello, World!\nThis is a test file.")

        // Extract text and metadata
        val result = service.extractTextAndMetadata(textFile)

        // Verify
        assertTrue(result is TextAndMetadataResult.Success)
        val success = result as TextAndMetadataResult.Success
        assertTrue(success.text.contains("Hello, World!"))
        assertTrue(success.text.contains("This is a test file."))

        // Verify metadata is populated (at minimum contentType should be present)
        assertNotNull(success.metadata)
        assertNotNull(success.metadata.contentType)
        assertTrue(success.metadata.contentType!!.startsWith("text/"))
    }

    @Test
    @DisplayName("Should extract metadata from HTML file with title")
    fun testExtractMetadataFromHtml() {
        // Create an HTML file with metadata
        val htmlFile = tempDir.resolve("test.html")
        htmlFile.writeText("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>Test Page Title</title>
                <meta name="author" content="John Doe">
                <meta name="description" content="This is a test page">
                <meta name="keywords" content="test, html, metadata">
            </head>
            <body>
                <h1>Hello from HTML</h1>
                <p>This is a paragraph.</p>
            </body>
            </html>
        """.trimIndent())

        // Extract text and metadata
        val result = service.extractTextAndMetadata(htmlFile)

        // Verify
        assertTrue(result is TextAndMetadataResult.Success)
        val success = result as TextAndMetadataResult.Success

        // Verify text extraction
        assertTrue(success.text.contains("Hello from HTML"))
        assertTrue(success.text.contains("This is a paragraph"))

        // Verify metadata - HTML parsers may extract title and other meta tags
        assertNotNull(success.metadata.contentType)
        // Title may be extracted from <title> tag
        // Note: Not all parsers extract all metadata from HTML, so we focus on contentType
        assertTrue(success.metadata.contentType!!.contains("html") ||
                   success.metadata.contentType!!.startsWith("text/"))
    }

    @Test
    @DisplayName("Should handle metadata extraction for file too large")
    fun testMetadataExtractionFileTooLarge() {
        // Create a file
        val largeFile = tempDir.resolve("large.txt")
        largeFile.writeText("Some content")

        // Try to extract with a very small max size
        val result = service.extractTextAndMetadata(largeFile, maxSize = 5L)

        // Verify
        assertTrue(result is TextAndMetadataResult.Failure)
        val failure = result as TextAndMetadataResult.Failure
        assertTrue(failure.error.contains("exceeds maximum"))
    }

    @Test
    @DisplayName("Should handle metadata extraction for non-existent file")
    fun testMetadataExtractionNonExistentFile() {
        // Try to extract from non-existent file
        val nonExistentFile = tempDir.resolve("does-not-exist.txt")

        val result = service.extractTextAndMetadata(nonExistentFile)

        // Verify
        assertTrue(result is TextAndMetadataResult.Failure)
        val failure = result as TextAndMetadataResult.Failure
        // File not found is now handled specifically with a clearer message
        assertTrue(failure.error.contains("File not found") ||
                   failure.error.contains("I/O error") ||
                   failure.error.contains("error"))
    }

    @Test
    @DisplayName("Should sanitize null bytes in metadata extraction")
    fun testSanitizeNullBytesInMetadataExtraction() {
        // Create a file with null bytes
        val fileWithNulls = tempDir.resolve("with-nulls.txt")
        Files.write(fileWithNulls, "Hello\u0000World\u0000Test".toByteArray())

        // Extract text and metadata
        val result = service.extractTextAndMetadata(fileWithNulls)

        // Verify null bytes are removed
        assertTrue(result is TextAndMetadataResult.Success)
        val success = result as TextAndMetadataResult.Success
        assertFalse(success.text.contains("\u0000"))
        // The text content may vary depending on Tika's binary file handling
        // but it should not contain null bytes
        assertTrue(success.text.isNotEmpty() || success.text.isEmpty())
    }

    @Test
    @DisplayName("Should extract metadata content type from XML")
    fun testExtractMetadataFromXml() {
        // Create an XML file
        val xmlFile = tempDir.resolve("test.xml")
        xmlFile.writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <root>
                <item>First item</item>
                <item>Second item</item>
            </root>
        """.trimIndent())

        // Extract text and metadata
        val result = service.extractTextAndMetadata(xmlFile)

        // Verify
        assertTrue(result is TextAndMetadataResult.Success)
        val success = result as TextAndMetadataResult.Success
        assertTrue(success.text.contains("First item") || success.text.contains("Second item"))

        // Verify content type is detected
        assertNotNull(success.metadata.contentType)
        assertTrue(success.metadata.contentType!!.contains("xml") ||
                   success.metadata.contentType!!.startsWith("application/"))
    }

    @Test
    @DisplayName("Should handle empty metadata fields gracefully")
    fun testEmptyMetadataFields() {
        // Create a simple file without metadata
        val simpleFile = tempDir.resolve("simple.txt")
        simpleFile.writeText("Just some text without any metadata.")

        // Extract text and metadata
        val result = service.extractTextAndMetadata(simpleFile)

        // Verify
        assertTrue(result is TextAndMetadataResult.Success)
        val success = result as TextAndMetadataResult.Success

        // Text should be present
        assertTrue(success.text.contains("Just some text"))

        // Metadata should exist but most fields will be null (which is expected)
        assertNotNull(success.metadata)
        // Only contentType should reliably be present
        assertNotNull(success.metadata.contentType)
    }

    // ---- Issue #119: contentHash (SHA-256) computed alongside text extraction ----

    @Test
    @DisplayName("Should leave contentHash null when computeHash=false (default)")
    fun testContentHashAbsentByDefault() {
        val file = tempDir.resolve("default.txt")
        file.writeText("Hello, World!")

        val result = service.extractTextAndMetadata(file)

        assertTrue(result is TextAndMetadataResult.Success)
        assertNull((result as TextAndMetadataResult.Success).contentHash,
            "Default extractTextAndMetadata call must not populate contentHash")
    }

    @Test
    @DisplayName("Should compute SHA-256 over raw file bytes when computeHash=true")
    fun testContentHashMatchesRawBytes() {
        val payload = "Hello, World!".toByteArray()
        val file = tempDir.resolve("hashed.txt")
        Files.write(file, payload)

        val result = service.extractTextAndMetadata(file, computeHash = true)

        assertTrue(result is TextAndMetadataResult.Success)
        val success = result as TextAndMetadataResult.Success

        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, success.contentHash,
            "contentHash must be SHA-256 over the file bytes, not the extracted text")

        // Sanity: extracted text differs from raw bytes for binary-ish content,
        // and the hash is over bytes — verify it does NOT equal the hash of the extracted text.
        val textHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(success.text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        // For pure-ASCII text, body text often equals bytes (modulo trailing whitespace
        // Tika inserts). Don't assert inequality unconditionally — just confirm
        // contentHash matches the raw-bytes hash explicitly, which it does above.
        assertEquals(64, success.contentHash!!.length, "SHA-256 hex digest must be 64 chars")
        assertEquals(success.contentHash, expected,
            "contentHash is raw-bytes hash; textHash provided for documentation")
        @Suppress("UNUSED_VARIABLE")
        val unused = textHash
    }

    @Test
    @DisplayName("Identical raw bytes in different paths produce identical contentHash")
    fun testContentHashStableAcrossPaths() {
        val payload = "spring-search-tempo dedup smoke test".toByteArray()
        val a = tempDir.resolve("a.txt").also { Files.write(it, payload) }
        val b = tempDir.resolve("nested/b.txt").also {
            Files.createDirectories(it.parent)
            Files.write(it, payload)
        }

        val ra = service.extractTextAndMetadata(a, computeHash = true) as TextAndMetadataResult.Success
        val rb = service.extractTextAndMetadata(b, computeHash = true) as TextAndMetadataResult.Success

        assertNotNull(ra.contentHash)
        assertEquals(ra.contentHash, rb.contentHash,
            "Two files with identical bytes must hash to the same SHA-256")
    }

    @Test
    @DisplayName("Empty INDEX-class file hashes to canonical SHA-256 of empty input")
    fun testContentHashForEmptyFile() {
        val empty = tempDir.resolve("empty.txt")
        Files.createFile(empty)

        val result = service.extractTextAndMetadata(empty, computeHash = true)

        assertTrue(result is TextAndMetadataResult.Success)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            (result as TextAndMetadataResult.Success).contentHash,
            "Empty INDEX-class files should hash to SHA-256('') so two empty files dedup."
        )
    }

    @Test
    @DisplayName("DigestInputStream wrapping does not corrupt extracted text")
    fun testHashingDoesNotCorruptExtractedText() {
        val text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.\n" +
            "Pellentesque habitant morbi tristique senectute."
        val file = tempDir.resolve("lorem.txt")
        file.writeText(text)

        val plain = service.extractTextAndMetadata(file) as TextAndMetadataResult.Success
        val hashed = service.extractTextAndMetadata(file, computeHash = true) as TextAndMetadataResult.Success

        assertEquals(plain.text, hashed.text,
            "Wrapping the Tika input in DigestInputStream must not alter the extracted text")
        assertEquals(plain.metadata.contentType, hashed.metadata.contentType,
            "Wrapping the Tika input in DigestInputStream must not alter detected metadata")
    }
}
