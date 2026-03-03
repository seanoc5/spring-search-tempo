package com.oconeco.remotecrawler.extraction

import org.apache.tika.Tika
import org.apache.tika.exception.TikaException
import org.apache.tika.exception.WriteLimitReachedException
import org.apache.tika.exception.ZeroByteFileException
import org.apache.tika.metadata.DublinCore
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.sax.BodyContentHandler
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream

/**
 * Service for extracting text content from various file formats using Apache Tika.
 *
 * Supports 400+ file formats including:
 * - Office documents (PDF, DOCX, XLSX, PPTX)
 * - OpenDocument formats (ODT, ODS, ODP)
 * - Plain text and source code files
 * - HTML, XML, and markup formats
 * - Archive formats with metadata
 * - Email formats (EML, MSG)
 */
class TextExtractionService(
    private val maxStringLength: Int = DEFAULT_MAX_STRING_LENGTH
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tika = Tika().apply {
        maxStringLength = this@TextExtractionService.maxStringLength
    }

    companion object {
        const val DEFAULT_MAX_STRING_LENGTH = 10 * 1024 * 1024 // 10MB text limit
    }

    /**
     * Extract text content from a file using Apache Tika.
     *
     * @param path Path to the file to extract text from
     * @param maxSize Maximum file size in bytes (files larger than this will be rejected)
     * @return TextExtractionResult indicating success or failure
     */
    fun extractText(path: Path, maxSize: Long = maxStringLength.toLong()): TextExtractionResult {
        return try {
            val fileSize = path.fileSize()
            if (fileSize == 0L) {
                log.debug("Skipping empty file (0 bytes): {}", path)
                return TextExtractionResult.Success("")
            }
            if (fileSize > maxSize) {
                log.warn("File {} exceeds max size ({} > {}), skipping text extraction", path, fileSize, maxSize)
                return TextExtractionResult.Failure("File size ($fileSize bytes) exceeds maximum ($maxSize bytes)")
            }

            log.debug("Extracting text from: {} (size: {} bytes)", path, fileSize)

            val text = tika.parseToString(path.toFile())
            val sanitized = text.replace("\u0000", "")

            log.debug("Successfully extracted {} characters from: {}", sanitized.length, path)
            TextExtractionResult.Success(sanitized)

        } catch (e: ZeroByteFileException) {
            log.debug("Empty file (0 bytes): {}", path)
            TextExtractionResult.Success("")
        } catch (e: TikaException) {
            log.warn("Tika failed to parse file: {}", path, e)
            TextExtractionResult.Failure("Tika parsing error: ${e.message ?: "Unknown Tika error"}")
        } catch (e: AccessDeniedException) {
            log.warn("Access denied reading file (permission issue): {}", path)
            TextExtractionResult.Failure("Access denied: ${e.message ?: path.toString()}")
        } catch (e: NoSuchFileException) {
            log.warn("File not found (may have been deleted): {}", path)
            TextExtractionResult.Failure("File not found: ${e.message ?: path.toString()}")
        } catch (e: IOException) {
            log.error("I/O error reading file: {}", path, e)
            TextExtractionResult.Failure("I/O error: ${e.message ?: "Unknown I/O error"}")
        } catch (e: OutOfMemoryError) {
            log.error("Out of memory extracting text from: {}", path, e)
            TextExtractionResult.Failure("Out of memory - file too large or complex")
        } catch (e: Exception) {
            log.error("Unexpected error extracting text from: {}", path, e)
            TextExtractionResult.Failure("Unexpected error: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Detect the MIME type of a file using Apache Tika.
     */
    fun detectMimeType(path: Path): String {
        return try {
            tika.detect(path.toFile())
        } catch (e: Exception) {
            log.warn("Failed to detect MIME type for: {}", path, e)
            "application/octet-stream"
        }
    }

    /**
     * Extract both text content and metadata from a file using Apache Tika.
     */
    fun extractTextAndMetadata(path: Path, maxSize: Long = maxStringLength.toLong()): TextAndMetadataResult {
        var textSize = -1
        return try {
            val fileSize = path.fileSize()
            if (fileSize == 0L) {
                log.debug("Skipping empty file (0 bytes): {}", path)
                return TextAndMetadataResult.Success("", FileMetadata())
            }
            if (fileSize > maxSize) {
                log.warn("File {} exceeds max size ({} > {}), skipping extraction", path, fileSize, maxSize)
                return TextAndMetadataResult.Failure("File size ($fileSize bytes) exceeds maximum ($maxSize bytes)")
            }

            log.debug("Extracting text and metadata from: {} (size: {} bytes)", path, fileSize)

            val metadata = Metadata()
            val parser = AutoDetectParser()
            val handler = BodyContentHandler(maxStringLength)

            path.inputStream().use { inputStream ->
                parser.parse(inputStream, handler, metadata)
            }

            val text = handler.toString()
            textSize = text.length
            val sanitizedText = text.replace("\u0000", "")

            val extractedMetadata = FileMetadata(
                author = metadata.get(TikaCoreProperties.CREATOR)
                    ?: metadata.get(DublinCore.CREATOR)
                    ?: metadata.get("Author")
                    ?: metadata.get("creator")
                    ?: metadata.get("meta:author"),
                title = metadata.get(TikaCoreProperties.TITLE)
                    ?: metadata.get(DublinCore.TITLE)
                    ?: metadata.get("title"),
                subject = metadata.get(DublinCore.SUBJECT)
                    ?: metadata.get("subject")
                    ?: metadata.get("dc:subject"),
                keywords = metadata.get("Keywords")
                    ?: metadata.get("keywords")
                    ?: metadata.get("meta:keyword")
                    ?: metadata.get("cp:keywords"),
                comments = metadata.get("Comments")
                    ?: metadata.get("comments")
                    ?: metadata.get("description"),
                creationDate = metadata.get(TikaCoreProperties.CREATED)
                    ?: metadata.get(DublinCore.CREATED)
                    ?: metadata.get("Creation-Date")
                    ?: metadata.get("created")
                    ?: metadata.get("meta:creation-date"),
                modifiedDate = metadata.get(TikaCoreProperties.MODIFIED)
                    ?: metadata.get(DublinCore.MODIFIED)
                    ?: metadata.get("Last-Modified")
                    ?: metadata.get("modified")
                    ?: metadata.get("dcterms:modified"),
                language = metadata.get(DublinCore.LANGUAGE)
                    ?: metadata.get(TikaCoreProperties.LANGUAGE)
                    ?: metadata.get("language")
                    ?: metadata.get("dc:language"),
                contentType = metadata.get(Metadata.CONTENT_TYPE),
                pageCount = metadata.get("xmpTPg:NPages")?.toIntOrNull()
                    ?: metadata.get("Page-Count")?.toIntOrNull()
                    ?: metadata.get("meta:page-count")?.toIntOrNull()
            )

            log.debug("Successfully extracted {} characters and metadata from: {}", sanitizedText.length, path)

            TextAndMetadataResult.Success(sanitizedText, extractedMetadata)

        } catch (e: ZeroByteFileException) {
            log.debug("Empty file (0 bytes): {}", path)
            TextAndMetadataResult.Success("", FileMetadata())
        } catch (e: TikaException) {
            log.warn("Tika failed to parse file: {}", path, e)
            TextAndMetadataResult.Failure("Tika parsing error: ${e.message ?: "Unknown Tika error"}")
        } catch (e: AccessDeniedException) {
            log.warn("Access denied reading file (permission issue): {}", path)
            TextAndMetadataResult.Failure("Access denied: ${e.message ?: path.toString()}")
        } catch (e: NoSuchFileException) {
            log.warn("File not found (may have been deleted): {}", path)
            TextAndMetadataResult.Failure("File not found: ${e.message ?: path.toString()}")
        } catch (e: IOException) {
            log.error("I/O error reading file: {}", path, e)
            TextAndMetadataResult.Failure("I/O error: ${e.message ?: "Unknown I/O error"}")
        } catch (e: OutOfMemoryError) {
            log.error("Out of memory extracting from: {}", path, e)
            TextAndMetadataResult.Failure("Out of memory - file too large or complex")
        } catch (e: WriteLimitReachedException) {
            val msg = "Text content size($textSize) exceeds max limit: $maxStringLength for path:'$path'"
            log.error(msg)
            TextAndMetadataResult.Failure(msg)
        } catch (e: Exception) {
            log.error("Unexpected error extracting from: {}", path, e)
            TextAndMetadataResult.Failure("Unexpected error: ${e.message ?: "Unknown error"}")
        }
    }
}

/**
 * Sealed class representing the result of a text extraction operation.
 */
sealed class TextExtractionResult {
    data class Success(val text: String) : TextExtractionResult()
    data class Failure(val error: String) : TextExtractionResult()
}

/**
 * Sealed class representing the result of extracting both text and metadata.
 */
sealed class TextAndMetadataResult {
    data class Success(val text: String, val metadata: FileMetadata) : TextAndMetadataResult()
    data class Failure(val error: String) : TextAndMetadataResult()
}

/**
 * Extracted file metadata from Tika.
 * All fields are nullable as not all documents contain all metadata.
 */
data class FileMetadata(
    val author: String? = null,
    val title: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val comments: String? = null,
    val creationDate: String? = null,
    val modifiedDate: String? = null,
    val language: String? = null,
    val contentType: String? = null,
    val pageCount: Int? = null
)
