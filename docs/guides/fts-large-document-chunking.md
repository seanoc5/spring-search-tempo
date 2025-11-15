# TODO: Full-Text Search for Large Documents (> 1MB)

**Status**: Not Implemented
**Priority**: Medium
**Created**: 2025-11-14

## Problem Statement

PostgreSQL's `tsvector` type has a hard limit of 1MB. Currently, we handle this by indexing only the first 250,000 characters of `body_text` in the FSFile table:

```sql
substring(body_text, 1, 250000)
```

This means documents larger than ~250KB have incomplete search coverage - terms appearing after the 250K cutoff cannot be found via full-text search.

## Current Affected Code

- **FSFile.kt** (lines 56-92): `ftsVector` field with 250KB substring limit
- **002-migrate-to-generated-fts.sql**: Migration script with same limit
- **FullTextSearchService.kt**: Search service (works correctly but only finds terms in first 250KB)

## Proposed Solution

### 1. Automatic Content Chunking

When a file's `body_text` exceeds 250KB:

1. **Detection** (in `CombinedCrawlProcessor` or similar):
   ```kotlin
   if (bodyText != null && bodyText.length > 250_000) {
       // Trigger chunking
       createOverlappingChunks(file, bodyText)
   }
   ```

2. **Create Overlapping Chunks**:
   - Split text into 200KB chunks with 25KB overlap
   - Store each chunk in `content_chunks` table
   - Mark chunk type as "LARGE_DOC_FRAGMENT"
   - Maintain chunk sequence via `chunk_number`

3. **Link Chunks to File**:
   - Set `content_chunks.concept_id = fsfile.id`
   - Each chunk gets its own `fts_vector` (auto-generated)

### 2. Search Result Aggregation

Update `FullTextSearchService` to:

1. **Search Both Tables**:
   - Query `fsfile.fts_vector` (first 250KB)
   - Query `content_chunks.fts_vector` (for overflow chunks)

2. **Deduplicate Results**:
   - Group chunks by `concept_id`
   - Return single result per file with best-matching snippet

3. **Snippet Selection**:
   - If match found in main file → use that snippet
   - If match only in chunks → use chunk snippet + chunk number indicator

### 3. Configuration

Add to `application.yml`:

```yaml
app:
  fts:
    chunk-threshold: 250000  # Characters before chunking kicks in
    chunk-size: 200000       # Characters per chunk
    chunk-overlap: 25000     # Overlap between chunks
```

## Implementation Steps

### Phase 1: Chunking Infrastructure
- [ ] Create `ChunkingService` in `base.service` package
- [ ] Add chunking configuration properties
- [ ] Implement overlapping text splitter
- [ ] Add unit tests for chunking logic

### Phase 2: Integration with Crawl
- [ ] Detect large documents in `CombinedCrawlProcessor`
- [ ] Call `ChunkingService.createChunksForLargeDocument()`
- [ ] Verify chunks are persisted with correct `fts_vector`
- [ ] Add integration test with large file

### Phase 3: Search Service Updates
- [ ] Update `FullTextSearchService.searchAll()` to query both tables
- [ ] Implement result deduplication logic
- [ ] Update snippet generation to indicate chunk number
- [ ] Add search tests for large documents

### Phase 4: UI/UX
- [ ] Update search results template to show "matched in chunk X of Y"
- [ ] Add "view full document" link that highlights match position
- [ ] Optional: Add chunk navigation in file detail view

## Example Use Case

**Before (Current)**:
- File: `large-manual.pdf` (2MB body_text)
- Search: "troubleshooting" (term appears at position 1.5MB)
- **Result**: ❌ Not found (term is beyond 250KB index limit)

**After (With Chunking)**:
- File: `large-manual.pdf` (2MB body_text)
- Chunks created:
  - Chunk 1: chars 0-200K
  - Chunk 2: chars 175K-375K (25K overlap)
  - ...
  - Chunk 8: chars 1.4MB-1.6MB ← Contains "troubleshooting"
- Search: "troubleshooting"
- **Result**: ✅ Found in chunk 8 of 10

## Performance Considerations

- **Storage**: ~20% overhead for overlap
- **Index Size**: Each chunk gets own `tsvector` → more index entries but better search coverage
- **Search Speed**: Minimal impact - GIN indexes handle multiple rows efficiently
- **Crawl Time**: Chunking adds ~0.1-0.5s per large file (one-time cost)

## Testing Plan

1. **Unit Tests**:
   - Test chunk splitting with various text sizes
   - Verify overlap calculation
   - Test edge cases (empty text, exact boundary matches)

2. **Integration Tests**:
   - Crawl file with 500KB text → verify 3 chunks created
   - Crawl file with 2MB text → verify 10 chunks created
   - Search for term in chunk 5 → verify found

3. **Manual Testing**:
   - Use actual large PDF (technical manual, research paper)
   - Verify search works across all chunks
   - Check UI displays chunk context correctly

## Related Issues

- Current workaround: `substring(body_text, 1, 250000)` in FSFile.kt:84
- Affects: ~5-10% of documents in typical knowledge base
- Higher impact for: technical documentation, academic papers, legal contracts

## References

- PostgreSQL tsvector docs: https://www.postgresql.org/docs/current/datatype-textsearch.html
- Elasticsearch chunking patterns (for inspiration)
- ADR-003: Apache Tika integration (related to text extraction)

---

**Last Updated**: 2025-11-14
**Assignee**: TBD
**Estimated Effort**: 2-3 days
