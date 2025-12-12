-- Migration: Add NLP fields to ContentChunk FTS vector
-- Date: 2025-12-11
-- Purpose: Enhance content chunk search with NLP-extracted data (nouns, verbs)
--          making NLP analysis results searchable via full-text search
--
-- NLP fields being added to FTS:
--   - nouns: Comma-separated lemmatized nouns (key concepts)
--   - verbs: Comma-separated lemmatized verbs (actions)
--
-- Weight assignments:
--   A (highest): Nouns - key concepts, most valuable for search
--   B (high):    Verbs - action words
--   C (normal):  Original text content
--
-- Named entities (JSON) are stored in named_entities column and can be queried separately.
-- They are not included in FTS vector to avoid JSON parsing complexity in generated columns.
--
-- Related: ContentChunk.kt, NLPChunkProcessor.kt

-- Step 1: Drop the existing FTS vector column (GENERATED columns can't be altered)
ALTER TABLE content_chunks DROP COLUMN IF EXISTS fts_vector CASCADE;

-- Step 2: Drop and recreate the index
DROP INDEX IF EXISTS idx_content_chunks_fts;

-- Step 3: Add enhanced GENERATED ALWAYS AS column with NLP fields
-- Weights: A=nouns (key concepts), B=verbs (actions), C=text (full content)
ALTER TABLE content_chunks ADD COLUMN fts_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(replace(nouns, ',', ' '), '')), 'A') ||
    setweight(to_tsvector('english', coalesce(replace(verbs, ',', ' '), '')), 'B') ||
    setweight(to_tsvector('english', coalesce(text, '')), 'C')
) STORED;

-- Step 4: Recreate the GIN index
CREATE INDEX idx_content_chunks_fts ON content_chunks USING GIN(fts_vector);

-- Step 5: Add index on sentiment for filtering
CREATE INDEX IF NOT EXISTS idx_content_chunks_sentiment ON content_chunks(sentiment);

-- Step 6: Add index on nlp_processed_at for finding unprocessed chunks
CREATE INDEX IF NOT EXISTS idx_content_chunks_nlp_processed ON content_chunks(nlp_processed_at);

-- Step 7: Update the search function to support sentiment filtering
DROP FUNCTION IF EXISTS search_chunks_with_sentiment(TEXT, TEXT, INTEGER, INTEGER);

CREATE OR REPLACE FUNCTION search_chunks_with_sentiment(
    search_query TEXT,
    sentiment_filter TEXT DEFAULT NULL,
    limit_results INTEGER DEFAULT 20,
    offset_results INTEGER DEFAULT 0
) RETURNS TABLE (
    id BIGINT,
    uri TEXT,
    label TEXT,
    snippet TEXT,
    rank REAL,
    sentiment TEXT,
    sentiment_score DOUBLE PRECISION,
    named_entities TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        c.id,
        COALESCE(f.uri, 'unknown') as uri,
        COALESCE(f.label, 'Chunk #' || c.chunk_number) as label,
        ts_headline('english', COALESCE(c.text, ''),
                    to_tsquery('english', search_query),
                    'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
        ts_rank(c.fts_vector, to_tsquery('english', search_query)) as rank,
        c.sentiment,
        c.sentiment_score,
        c.named_entities
    FROM content_chunks c
    LEFT JOIN fsfile f ON c.concept_id = f.id
    WHERE c.fts_vector @@ to_tsquery('english', search_query)
      AND (sentiment_filter IS NULL OR c.sentiment = sentiment_filter)
    ORDER BY rank DESC
    LIMIT limit_results
    OFFSET offset_results;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION search_chunks_with_sentiment IS 'Search content chunks with optional sentiment filtering and NLP-enhanced ranking';

-- Step 8: Update comments
COMMENT ON COLUMN content_chunks.fts_vector IS 'Auto-generated FTS vector with NLP-enhanced fields (A: nouns, B: verbs, C: text)';

-- Migration complete!
-- ContentChunk FTS now includes:
--   - Nouns (weighted highest for concept search)
--   - Verbs (weighted high for action search)
--   - Original text (normal weight)
--
-- Named entities are stored in the named_entities JSON column and returned in search results.
--
-- Example searches that now work better:
--   - "computer software" will boost documents with these nouns
--   - Sentiment filtering: search_chunks_with_sentiment('crisis', 'NEGATIVE')
