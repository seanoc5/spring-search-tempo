# ADR 006: Chunking strategy for FSFiles and EmailMessages with `body_text` > 1 MB

## Status

Accepted (extended for `email_message` in issue #161)

## Context

`FSFile.body_text` is the column that holds the text Tika extracted from a
crawled file. The column is `text` (no width cap) and is then folded into a
PostgreSQL FTS `tsvector` via a GENERATED column:

```sql
setweight(to_tsvector('english', coalesce(substring(body_text, 1, 250000), '')), 'C')
```

The `substring(.., 1, 250000)` cap exists because a PostgreSQL `tsvector`
can't exceed ~1 MB serialized — beyond that, the INSERT errors with
`string is too long for tsvector`. The comment on `FSFile.ftsVector` flags
this as a known limitation (the original TODO that drove issue #147):

> Currently, only the first ~250K characters are indexed.

The follow-on effects as crawl coverage grows (logs, dumps, exports, long
PDFs after Tika extraction) are:

- `fs_file` table rows balloon — a 5 MB extracted text means a 5 MB
  `body_text` cell, which inflates `SELECT *` patterns, autovacuum work,
  and dump sizes.
- Stanford CoreNLP throughput collapses on very long "sentences" that
  Tika sometimes emits (PDF column reflows, MD code blocks, etc.).
- `to_tsvector` on enormous inputs is slow even with the `substring`
  guard, because the input still has to be materialized.
- OOM risk during NLP enrichment passes.

The chunking pipeline (`SentenceChunker` → `ContentChunk`) already exists,
already maintains its own per-chunk `fts_vector`, and is already wired into
the unified `search_full_text()` function in
`docs/sql/essential-postgres-features.sql`. So the missing piece is not
"how do we index large bodies" — it's "how do we stop storing them whole
in `fs_file.body_text`."

## Decision

1. **Threshold**: `app.crawl.large-body-threshold-chars`, default
   **1,048,576**. The unit is *characters* — `body_text` is a PostgreSQL
   `text` column, `LENGTH(body_text)` reports characters, and the
   existing `substring(body_text, 1, 250000)` cap on `fs_file.fts_vector`
   is also character-based. For ASCII content 1,048,576 chars ≈ 1 MB on
   disk; mostly-UTF-8 content can be 2–4× larger byte-wise. Operators
   who need a tight on-disk ceiling should dial the threshold down
   accordingly.
2. **Strategy A — preview-in-`body_text`, full coverage in chunks**:
   - During ingestion, extracted text is left intact on the FSFile until
     the chunking step has finished consuming it.
   - The chunking step (`SentenceChunker` and friends) reads the *full*
     extracted text and produces `ContentChunk` rows spanning the entire
     document — same as today.
   - Immediately after the chunks land for a file, the chunking writer
     truncates `fs_file.body_text` to the configured threshold. A short
     marker (`"…[truncated: N chars; full content in chunks]"`) is
     appended so an operator looking at the row understands what
     happened. The bounded `body_text` continues to drive
     `fs_file.fts_vector` (weight C) and to provide a cheap preview for
     list views, but the full document is only stored once — in chunks.
3. **Search-layer behavior unchanged**:
   - `content_chunks.fts_vector` already covers the chunk text
     (`setweight(to_tsvector('english', coalesce(text, '')), 'C')`),
     and `search_full_text()` already unions results from both tables.
     So a query for a term that sits at byte-offset 4 MB of a 5 MB
     document is found via the chunk row, even though `body_text`
     stops at 1 MB.
   - `essential-postgres-features.sql` does NOT need to change. The
     `substring(body_text, 1, 250000)` cap on
     `fs_file.fts_vector` is now strictly cheaper than before — most
     `body_text` columns will fit inside that cap because they've been
     truncated to ≤ 1 MB at write time — and the chunk FTS picks up the
     overflow.
4. **Backfill**: existing rows with `body_text` > threshold AND a non-null
   `chunked_at` (so chunks already exist) can be truncated via a one-shot
   admin REST endpoint (`POST /api/admin/truncate-large-bodies`). Rows
   that haven't been chunked yet are left alone — they'll be picked up by
   the normal chunking pipeline and truncated on the next run.
5. **Email messages (issue #161)**: the same Strategy A applies to
   `email_message.body_text`. The Spring Batch `EmailQuickSyncJobBuilder`
   already runs a `EmailChunking_<account>` step after body enrichment;
   `EmailChunkWriter` now calls
   `EmailMessageService.truncateBodyTextToThreshold` once an email's
   chunks have landed. The threshold knob is the same
   (`app.crawl.large-body-threshold-chars`), and the
   `email_message.fts_vector` column already carries the same
   `substring(body_text, 1, 250000)` cap as `fs_file.fts_vector`, so the
   FTS path is identical. Backfill ships as a sibling endpoint:
   `POST /api/admin/truncate-large-email-bodies`. EmailMessage has no
   `chunked_at` timestamp of its own — the backfill query stands in by
   requiring at least one `ContentChunk` to point at the message.
6. **Defensive write-time guard (issue #161, item f)**:
   `EmailMessageServiceImpl.updateBodyAndComplete` (the Pass-2 body
   enrichment writer) now caps `body_text` at
   `large-body-threshold-chars × 5` *before* the UPDATE fires, logging
   a WARN when it kicks in. Normal flow never trips this guard — the
   body is well under the multiplier and Pass 3 chunking handles the
   real truncation a few seconds later. The guard only fires if
   something pathological gets past the upstream extractor (a 50 MB
   plaintext mail-list digest, for instance), turning a tsvector batch
   abort into a single WARN-logged row. Same belt-and-suspenders
   pattern that other large-text sites in the codebase rely on.

## Rationale

### Why not Option B (skip `body_text` entirely above threshold)?

Option B would set `body_text = NULL` (or a marker only) once the file is
chunked. It's the simplest "store once" design, but it has two costs:

- **Preview**: many UI list views render the first ~200 chars of
  `body_text` as a snippet. NULL forces every such view through a join to
  the first chunk, which is a measurable per-row cost.
- **FTS regression for files barely over the threshold**: a 1.2 MB
  `body_text` would lose its `fs_file.fts_vector` content entirely. The
  chunk FTS still covers it, but title-weighted ranking on the FSFile
  becomes weaker.

Option A keeps the preview-and-FTS path working for the *first* MB of
every file, and shifts only the overflow to chunks. The extra cost is
that the first ~1 MB of each large document appears in two places —
`body_text` and the first ~250 chunks. That overlap is bounded and
deliberate; we're not storing a second *full* copy.

### Why truncate after chunking, not before?

Chunking is a downstream step in the same Spring Batch job (see
`FsCrawlJobBuilder.buildJob`: `.start(combinedCrawlStep).next(chunkingStep)`).
The chunker reads `body_text` off the FSFile entity that the crawl step
just wrote. If we truncated at extraction time, the chunker would only
see the first 1 MB and produce no chunks for the overflow — losing the
exact coverage this ADR is meant to gain. Truncating in the chunking
writer keeps the in-flight `body_text` bloated for the brief window
between extraction and chunking (a few seconds within the same job
execution) in exchange for full chunk coverage.

For files where chunking never runs (chunking step disabled in some
operator-driven scenarios, or a chunking failure mid-batch), `body_text`
stays full. That's the desired safety property — we never throw away
content that isn't represented elsewhere.

### Why 1 MB default, not 250 KB?

250 KB matches the existing `tsvector` substring cap, but the cap is
about FTS, not about row size. As a preview-and-cheap-FTS budget, 1 MB
(~250 pages of plain text) covers almost every file we expect to crawl
without truncation, and only bites on log dumps / exports / book-length
documents. Operators can dial the threshold down via
`app.crawl.large-body-threshold-chars` if they want a tighter row-size
ceiling.

### Why not chunk inline during extraction?

That would let us skip the "truncate after the fact" dance, but the cost
is invasive: the extraction-time processor (`CombinedCrawlProcessor` /
`IndexingProcessor`) would have to own chunking, persisting chunks, and
linking them to a not-yet-persisted FSFile. The existing pipeline
splits these concerns cleanly across two batch steps with their own
checkpointing, parallelism, and listener wiring. The "truncate after
chunking" hook is a one-line addition to the existing `ChunkWriter` and
keeps all of that intact.

## Consequences

### Positive

- `fs_file.body_text` rows are bounded by the threshold (default 1 MB)
  for every file that has been chunked. Table size, dump size, and
  `SELECT *` cost stop growing linearly with document size.
- `ContentChunk` rows cover the *full* extracted text for every file,
  so chunk-FTS works for content at any offset.
- NLP throughput improves on long documents — the sentence chunker
  already splits long sentences, and we're no longer paying for one
  giant blob in `body_text` on top of that.
- `essential-postgres-features.sql` is unchanged.

### Negative

- For files between the chunking threshold and the Tika extraction cap
  (`app.text-extraction.max-text-length`, default 10 MB), the first
  ~1 MB of text is stored twice — once in the truncated `body_text`,
  once split across the leading chunks. This is deliberate and
  documented above; it preserves the cheap preview and the FSFile-level
  FTS path.
- Brief window of bloated `body_text` between extraction and chunking
  within the same job execution. If a job crashes between the two steps,
  the row stays bloated until the next chunking pass.

### Neutral

- Existing operators see no UI or REST changes other than the new
  backfill endpoint. The truncation marker on `body_text` is the only
  visible signal that a file was over the threshold.

## References

- Original TODO: `src/main/kotlin/com/oconeco/spring_search_tempo/base/domain/FSFile.kt:143`
- Related: `app.text-extraction.max-text-length` (Tika body-handler cap)
- Issue #147 — FSFile body bound
- Issue #161 — EmailMessage body bound (extension of the same policy)
- PostgreSQL tsvector limit: https://www.postgresql.org/docs/current/textsearch-limitations.html
