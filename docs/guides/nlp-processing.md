# NLP Processing Guide

This guide covers the Natural Language Processing (NLP) capabilities in Spring Search Tempo, including setup, triggering, and customization.

## Overview

Spring Search Tempo uses Stanford CoreNLP to analyze text content extracted from files. The NLP pipeline performs:

- **Named Entity Recognition (NER)**: Identifies people, organizations, locations, dates, money, etc.
- **Part-of-Speech (POS) Tagging**: Labels each word with its grammatical role (noun, verb, adjective, etc.)
- **Lemmatization**: Reduces words to their base form (running → run)
- **Sentiment Analysis**: Classifies text as POSITIVE, NEGATIVE, or NEUTRAL

## Architecture

```
File Crawl Job                    NLP Processing Job
     │                                   │
     ▼                                   ▼
┌─────────────┐    auto-trigger    ┌─────────────┐
│  FSFile     │ ───────────────►   │ContentChunk │
│  (bodyText) │                    │  (text)     │
└─────────────┘                    └─────────────┘
     │                                   │
     ▼                                   ▼
┌─────────────┐                    ┌─────────────┐
│ChunkingStep │                    │NLPProcessor │
│ (sentences) │                    │ (CoreNLP)   │
└─────────────┘                    └─────────────┘
     │                                   │
     ▼                                   ▼
┌─────────────┐                    ┌─────────────┐
│ContentChunk │                    │ContentChunk │
│  (created)  │                    │ (enriched)  │
└─────────────┘                    └─────────────┘
```

## Key Components

### NLPService (`base/service/NLPService.kt`)

Interface defining NLP operations:

```kotlin
interface NLPService {
    fun analyze(text: String): NLPAnalysisResult
    fun extractNamedEntities(text: String): List<NamedEntity>
    fun performPOSTagging(text: String): List<POSTag>
    fun analyzeSentiment(text: String): SentimentResult
}
```

### StanfordNLPService

Implementation using Stanford CoreNLP. The pipeline is lazily initialized (~5 seconds on first use).

### NLPProcessingJobConfiguration (`batch/nlp/`)

Spring Batch job that processes ContentChunks:

```kotlin
@Bean
fun nlpProcessingJob(): Job {
    return JobBuilder("nlpProcessingJob", jobRepository)
        .start(nlpProcessingStep())
        .build()
}
```

### NLPJobLauncher

Service for launching the NLP job programmatically:

```kotlin
@Service
class NLPJobLauncher(
    private val jobLauncher: JobLauncher,
    private val nlpProcessingJob: Job
) {
    fun launchNLPJob(triggeredBy: String = "manual"): JobExecution
}
```

### NLPAutoTriggerListener

JobExecutionListener that automatically triggers NLP processing after a successful file crawl:

```kotlin
@Component
class NLPAutoTriggerListener(
    private val nlpJobLauncher: NLPJobLauncher,
    @Value("\${app.nlp.auto-trigger:true}")
    private val autoTriggerEnabled: Boolean
) : JobExecutionListener
```

## Triggering NLP Processing

### 1. Automatic (Default)

NLP processing runs automatically after a successful file crawl job. This is the recommended approach.

**Configuration** (`application.yml`):

```yaml
app:
  nlp:
    auto-trigger: true  # Default, set to false to disable
```

The auto-trigger only fires when:
- The file crawl job completes successfully
- At least one ContentChunk was created

### 2. REST API

Trigger NLP processing via HTTP:

```bash
# Trigger NLP processing
curl -X POST http://localhost:8089/api/nlp/process

# Response:
{
  "executionId": 123,
  "jobName": "nlpProcessingJob",
  "status": "STARTED",
  "message": "NLP processing job started successfully"
}

# Check NLP status
curl http://localhost:8089/api/nlp/status

# Response:
{
  "enabled": true,
  "autoTriggerEnabled": true,
  "message": "NLP processing is available. Use POST /api/nlp/process to trigger manually."
}
```

### 3. Web UI

POST to `/nlp/process` from any form:

```html
<form action="/nlp/process" method="post">
  <input type="hidden" name="redirectTo" value="/crawlConfigs">
  <button type="submit">Run NLP Processing</button>
</form>
```

### 4. Programmatic

Inject and use `NLPJobLauncher`:

```kotlin
@Service
class MyService(private val nlpJobLauncher: NLPJobLauncher) {

    fun processContent() {
        // ... do something ...
        nlpJobLauncher.launchNLPJob(triggeredBy = "MyService")
    }
}
```

## ContentChunk NLP Fields

After NLP processing, ContentChunk entities are enriched with:

| Field | Type | Description |
|-------|------|-------------|
| `namedEntities` | String (JSON) | `[{"text": "John", "type": "PERSON", "startOffset": 0, "endOffset": 4}]` |
| `tokenAnnotations` | String (JSON) | `[{"token": "running", "tag": "VBG", "lemma": "run"}]` |
| `nouns` | String | Comma-separated lemmatized nouns: `"report, analysis, data"` |
| `verbs` | String | Comma-separated lemmatized verbs: `"run, analyze, process"` |
| `sentiment` | String | `POSITIVE`, `NEGATIVE`, or `NEUTRAL` |
| `sentimentScore` | Double | 0.0 (very negative) to 1.0 (very positive) |
| `nlpProcessedAt` | OffsetDateTime | Timestamp when NLP processing completed |

## NLP-Enhanced Full-Text Search

NLP-extracted data is automatically included in PostgreSQL full-text search (FTS). The `fts_vector` column on ContentChunk combines:

| Weight | Field | Description |
|--------|-------|-------------|
| A (highest) | `nouns` | Key concepts - most valuable for precise search |
| B (high) | `verbs` | Action words |
| C (normal) | `text` | Original chunk text |

This weighting means searching for "report analysis" will rank chunks containing these nouns higher than chunks where these words appear in regular text.

### Searching with Sentiment Filter

The REST API supports filtering search results by sentiment:

```bash
# Search for "crisis" in NEGATIVE sentiment chunks
curl "http://localhost:8089/api/search/chunks?q=crisis&sentiment=NEGATIVE"

# Search for "growth" in POSITIVE sentiment chunks
curl "http://localhost:8089/api/search/chunks?q=growth&sentiment=POSITIVE"

# Search without sentiment filter (all results)
curl "http://localhost:8089/api/search/chunks?q=growth"
```

### Search Response with NLP Data

The chunk search endpoint returns NLP data:

```json
{
  "content": [
    {
      "id": 12345,
      "fileUri": "/path/to/document.pdf",
      "fileLabel": "Annual Report 2024",
      "chunkNumber": 3,
      "chunkType": "Sentence",
      "snippet": "The company experienced significant <b>growth</b> in Q4...",
      "rank": 0.85,
      "sentiment": "POSITIVE",
      "sentimentScore": 0.78,
      "namedEntities": "[{\"text\":\"Q4\",\"type\":\"DATE\"}]"
    }
  ],
  "totalElements": 42
}
```

### Database Migration

For existing databases, run the migration script to update the FTS vector:

```bash
psql -h localhost -p 5433 -U postgres -d spring_search_tempo -f docs/sql/003-add-nlp-to-fts.sql
```

This migration:
1. Drops and recreates the `fts_vector` column with NLP field weights
2. Adds indexes on `sentiment` and `nlp_processed_at` columns
3. Creates a `search_chunks_with_sentiment()` PostgreSQL function

## Processing Flow

1. **Reader**: Fetches ContentChunks where `nlpProcessedAt IS NULL AND text IS NOT NULL`
2. **Processor**:
   - Maps entity to DTO
   - Calls `nlpService.analyze(text)`
   - Extracts entities, POS tags, nouns, verbs, sentiment
   - Sets `nlpProcessedAt` timestamp
3. **Writer**: Updates entity with NLP results

## Performance Considerations

### Stanford CoreNLP

- **Initialization**: ~5 seconds on first use (lazy loading)
- **Processing**: ~1-5 seconds per document (CPU-intensive)
- **Memory**: Models require ~1GB heap space

### Batch Configuration

Default settings in `NLPProcessingJobConfiguration`:

```kotlin
private const val CHUNK_SIZE = 10  // Process 10 chunks per transaction
```

### Skipping Large Chunks

Chunks over 10,000 characters are skipped to avoid excessive processing time:

```kotlin
if (text.length > MAX_TEXT_LENGTH) {
    log.warn("Skipping chunk {} - text too long", item.id)
    item.nlpProcessedAt = OffsetDateTime.now()  // Mark as processed
    return item
}
```

## Configuration Reference

### application.yml

```yaml
app:
  nlp:
    auto-trigger: true  # Enable/disable auto-trigger after crawl (default: true)
```

### Disabling NLP Entirely

To completely disable NLP processing:

1. Set `app.nlp.auto-trigger: false`
2. Don't call the REST API or UI endpoints
3. Optionally, exclude CoreNLP from the build (reduces JAR size by ~500MB)

## Troubleshooting

### NLP Job Not Running

1. **Check auto-trigger is enabled**: `app.nlp.auto-trigger: true`
2. **Verify chunks exist**: Query `SELECT COUNT(*) FROM content_chunks WHERE nlp_processed_at IS NULL AND text IS NOT NULL`
3. **Check job status**: Look for `nlpProcessingJob` in Spring Batch metadata tables

### Out of Memory

CoreNLP models are large. Increase heap size:

```bash
JAVA_OPTS="-Xmx2g" ./gradlew bootRun
```

### Slow Processing

- NLP is CPU-intensive; consider running on a dedicated thread pool
- Reduce chunk size if memory is constrained
- Process only high-value content (ANALYZE level files)

### Entity Recognition Issues

Stanford CoreNLP works best with:
- Well-formatted English text
- Complete sentences
- Standard capitalization

Poor results may indicate:
- OCR errors in source documents
- Non-English content
- Heavily abbreviated text

## Extending NLP

### Adding New Annotators

Modify `StanfordNLPService` to add CoreNLP annotators:

```kotlin
val props = Properties().apply {
    setProperty("annotators", "tokenize,ssplit,pos,lemma,ner,parse,sentiment,coref")
    // Add coref for coreference resolution
}
```

### Custom Entity Types

For domain-specific entities (e.g., product names, legal terms), consider:
1. Training a custom NER model
2. Using regex patterns post-processing
3. Integrating a domain-specific NLP service

### Alternative NLP Providers

The `NLPService` interface allows swapping implementations:
- spaCy (via REST API)
- Hugging Face Transformers
- Cloud NLP services (Google, AWS, Azure)

---

**Last Updated**: 2025-12-11
