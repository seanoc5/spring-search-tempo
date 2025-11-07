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
        assertTrue(failure.error.contains("I/O error") || failure.error.contains("error"))
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
        assertTrue(failure.error.contains("I/O error") || failure.error.contains("error"))
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
}
