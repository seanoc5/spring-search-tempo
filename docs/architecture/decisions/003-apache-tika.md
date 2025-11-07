# ADR 003: Use Apache Tika for Text Extraction

## Status

Accepted

## Context

Spring Search Tempo needs to extract text content from various file formats for indexing and search. Files to support include:
- Office documents (PDF, DOCX, XLSX, PPTX)
- Plain text files (TXT, MD, source code)
- Web formats (HTML, XML)
- Archives (ZIP, TAR, GZIP)
- Emails (EML, MSG)
- Images (EXIF metadata)
- And many more...

Options considered:
1. **Simple File Reading**: Read files as UTF-8 text
2. **Format-Specific Libraries**: Use PDFBox, POI, etc. separately
3. **Apache Tika**: Unified text extraction framework
4. **Commercial Solutions**: Paid text extraction services

## Decision

Use Apache Tika 2.9.1 as the unified text extraction library.

## Rationale

### Why Apache Tika?

1. **Comprehensive Format Support**
   - 400+ file formats supported out-of-the-box
   - Automatic format detection based on content (not just extension)
   - Single API for all formats
   - Regularly updated with new formats

2. **Metadata Extraction**
   - Document properties (author, title, creation date)
   - EXIF data from images
   - Email headers
   - Archive contents
   - Custom metadata

3. **Production-Ready**
   - Mature project (since 2007)
   - Used by major projects (Apache Solr, Elasticsearch)
   - Active development and security updates
   - Extensive documentation

4. **Spring Integration**
   - Simple to add as dependency
   - No complex configuration needed
   - Works well with Spring Boot
   - Can be easily mocked for testing

5. **Extensibility**
   - Custom parsers can be added
   - Configurable per format
   - Resource management built-in
   - Error handling included

### Comparison with Alternatives

**Simple File Reading**:
- ✅ Fast and simple
- ❌ Only works for plain text
- ❌ No metadata extraction
- ❌ Can't handle binary formats
- ❌ No encoding detection

**Format-Specific Libraries**:
- ✅ Fine-grained control per format
- ❌ Multiple dependencies to manage
- ❌ Different APIs to learn
- ❌ Format detection logic needed
- ❌ More code to maintain

**Commercial Solutions**:
- ✅ Potentially better support
- ❌ Licensing costs
- ❌ Vendor lock-in
- ❌ External service dependency
- ❌ Privacy concerns (for cloud services)

## Architecture

### Service Design

```kotlin
@Service
class TextExtractionService {
    private val tika = Tika()
    private val parser = AutoDetectParser()

    fun extractText(path: Path, maxSize: Long): TextExtractionResult {
        // Extract text content only
    }

    fun extractTextAndMetadata(path: Path, maxSize: Long): TextAndMetadataResult {
        // Extract text + metadata (author, title, etc.)
    }

    fun detectMimeType(path: Path): String {
        // Detect content type without full parse
    }
}
```

### Result Types

```kotlin
sealed class TextExtractionResult {
    data class Success(val text: String) : TextExtractionResult()
    data class Failure(val error: String) : TextExtractionResult()
}

data class FileMetadata(
    val author: String?,
    val title: String?,
    val contentType: String?,
    val pageCount: Int?
    // ... other metadata fields
)
```

### Integration Points

1. **Batch Processing**: FileProcessor uses Tika during crawling
2. **On-Demand**: REST API can trigger re-extraction
3. **Testing**: Easily mocked with test fixtures

## Consequences

### Positive

1. **Unified API**: Single service for all file types
2. **Comprehensive Support**: 400+ formats handled
3. **Metadata Rich**: Document properties extracted automatically
4. **Maintainable**: One dependency instead of many
5. **Well-Tested**: Mature library with good test coverage
6. **Future-Proof**: New formats added regularly

### Negative

1. **Dependency Size**: Tika and parsers add ~50MB to JAR
2. **Memory Usage**: Large files can consume significant memory
3. **Processing Time**: Complex formats (PDF) can be slow
4. **Error Handling**: Some formats may fail to parse
5. **Security**: Parsing untrusted files has inherent risks

### Mitigations

**Dependency Size**:
- Acceptable trade-off for functionality
- Can use `tika-parsers-standard-package` to include only needed parsers
- Alternative: Use `tika-core` + selective parser dependencies

**Memory Usage**:
- Implement file size limits (10MB text default)
- Stream large files when possible
- Configure Tika's max string length

**Processing Time**:
- Async processing for large files
- Timeouts for extraction operations
- Skip extraction for very large files

**Error Handling**:
- Graceful degradation (store error message)
- Retry logic for transient failures
- Logging for debugging

**Security**:
- Don't trust user-provided files in web UI (planned for future)
- Run with minimal permissions
- Keep Tika updated for security patches
- Consider sandboxing for untrusted content

## Implementation Details

### Dependencies

**build.gradle.kts**:
```kotlin
dependencies {
    // Apache Tika for text extraction
    implementation("org.apache.tika:tika-core:2.9.1")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.1")
}
```

### Configuration

**Reasonable Defaults**:
- Max text size: 10MB (10 * 1024 * 1024 bytes)
- Max string length: 100,000 characters (Tika default)
- Timeout: 60 seconds per file
- Encoding: Auto-detect

**Future Configuration** (application.yml):
```yaml
text-extraction:
  max-file-size: 10485760  # 10MB
  max-string-length: 100000
  timeout: 60
  fail-on-error: false  # Continue on extraction failure
```

### Error Handling Strategy

```kotlin
when (val result = textExtractionService.extractText(path)) {
    is TextExtractionResult.Success -> {
        // Use extracted text
        file.bodyText = result.text
    }
    is TextExtractionResult.Failure -> {
        // Log error but continue processing
        log.warn("Extraction failed for {}: {}", path, result.error)
        file.bodyText = "[Extraction failed: ${result.error}]"
    }
}
```

### Supported Formats (Examples)

**Office Documents**:
- PDF (via PDFBox)
- Microsoft Office: DOC, DOCX, XLS, XLSX, PPT, PPTX
- OpenDocument: ODT, ODS, ODP
- Rich Text Format (RTF)

**Text & Code**:
- Plain text (TXT)
- Markdown (MD)
- Source code (Java, Kotlin, Python, JavaScript, etc.)
- CSV, TSV

**Web & Data**:
- HTML, XHTML
- XML, JSON
- YAML

**Archives**:
- ZIP, TAR, GZIP, BZIP2
- JAR, WAR, EAR
- 7z, RAR (with limitations)

**Email**:
- EML (RFC 822)
- MSG (Outlook)
- MBOX

**Images** (metadata only):
- JPEG (EXIF)
- PNG, TIFF, GIF
- RAW formats (CR2, NEF, etc.)

**Audio/Video** (metadata only):
- MP3, MP4, AVI, MOV
- Metadata: artist, title, duration, etc.

## Testing Strategy

### Unit Tests

```kotlin
@ExtendWith(MockitoExtension::class)
class TextExtractionServiceTest {

    lateinit var service: TextExtractionService

    @Test
    fun `should extract text from PDF`() {
        val path = Paths.get("src/test/resources/test.pdf")
        val result = service.extractText(path, 10_000_000)

        assertThat(result).isInstanceOf(TextExtractionResult.Success::class.java)
        val text = (result as TextExtractionResult.Success).text
        assertThat(text).contains("expected content")
    }

    @Test
    fun `should extract metadata from DOCX`() {
        val path = Paths.get("src/test/resources/test.docx")
        val result = service.extractTextAndMetadata(path, 10_000_000)

        assertThat(result).isInstanceOf(TextAndMetadataResult.Success::class.java)
        val success = result as TextAndMetadataResult.Success
        assertThat(success.metadata.author).isEqualTo("Test Author")
    }
}
```

### Test Fixtures

Maintain test files for each supported format:
```
src/test/resources/
├── test.pdf
├── test.docx
├── test.txt
├── test.html
├── test.zip
└── test.eml
```

## Performance Considerations

### Benchmarks (Approximate)

| Format | File Size | Extraction Time |
|--------|-----------|----------------|
| TXT    | 1MB       | <100ms         |
| HTML   | 1MB       | ~200ms         |
| DOCX   | 1MB       | ~500ms         |
| PDF    | 1MB/10pg  | ~1s            |
| XLSX   | 1MB       | ~800ms         |

**Note**: Times vary based on complexity and hardware

### Optimization Strategies

1. **Caching**: Don't re-extract if unchanged (use lastModified timestamp)
2. **Async Processing**: Extract text in background jobs
3. **Selective Extraction**: Skip text for `LOCATE` level processing
4. **Size Limits**: Don't attempt to extract huge files
5. **Parallel Processing**: Use Spring Batch chunking for multiple files

## Alternatives Considered

### Alternative 1: Format-Specific Libraries

**Approach**: Use PDFBox, Apache POI, etc. directly

**Pros**:
- More control over each format
- Can tune per-format settings
- Potentially smaller dependency footprint

**Cons**:
- Multiple APIs to learn and maintain
- Need format detection logic
- More dependencies to manage
- Inconsistent error handling

**Decision**: ❌ Rejected - unnecessary complexity

### Alternative 2: Simple UTF-8 Reading

**Approach**: Just read files as text with standard Java APIs

**Pros**:
- Very fast
- No dependencies
- Simple code

**Cons**:
- Only works for plain text files
- No metadata extraction
- Can't handle binary formats (PDF, DOCX, etc.)
- Poor encoding detection

**Decision**: ❌ Rejected - insufficient for requirements

### Alternative 3: External Service (e.g., AWS Textract)

**Approach**: Use cloud-based text extraction API

**Pros**:
- Offload processing
- Potentially better quality (ML-based)
- No local resource usage

**Cons**:
- Cost per document
- Network latency
- Privacy concerns
- External dependency
- Requires internet access

**Decision**: ❌ Rejected - want self-contained solution

## Future Enhancements

1. **Custom Parsers**: Add project-specific format support
2. **Language Detection**: Identify document language
3. **OCR Integration**: Extract text from images/scanned PDFs (Apache Tesseract)
4. **Encoding Detection**: Better charset detection
5. **Configuration**: Externalize limits and timeouts
6. **Monitoring**: Track extraction success/failure rates

## References

- [Apache Tika Official Site](https://tika.apache.org/)
- [Tika Documentation](https://cwiki.apache.org/confluence/display/TIKA)
- [Supported Formats](https://tika.apache.org/2.9.1/formats.html)
- [Tika in Action (book)](https://www.manning.com/books/tika-in-action)

## Date

2025-11-07

## Reviewed

- Initial implementation: 2025-11-07
- Tested with PDF, DOCX, TXT: 2025-11-07
