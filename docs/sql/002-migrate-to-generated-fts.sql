-- Migration: Convert FTS from trigger-based to GENERATED ALWAYS AS columns
-- Date: 2025-11-14
-- Purpose: Migrate from manual trigger-based tsvector maintenance to PostgreSQL
--          GENERATED columns for cleaner, more maintainable FTS implementation
--
-- IMPORTANT: This migration addresses the 1MB tsvector size limit by:
--            1. Limiting body_text indexing to first 250,000 characters
--            2. Future: Implement chunking for documents > 1MB (see TODO in FSFile.kt)
--
-- Related: FSFile.kt lines 56-92, ContentChunks.kt lines 55-78

-- Step 1: Drop materialized view (depends on fts_vector columns)
DROP MATERIALIZED VIEW IF EXISTS search_stats CASCADE;

-- Step 2: Drop old triggers
DROP TRIGGER IF EXISTS fsfile_fts_update ON fsfile;
DROP TRIGGER IF EXISTS content_chunks_fts_update ON content_chunks;

-- Step 3: Drop old trigger functions
DROP FUNCTION IF EXISTS fsfile_fts_trigger();
DROP FUNCTION IF EXISTS content_chunks_fts_trigger();

-- Step 4: Drop existing fts_vector columns (they were regular columns managed by triggers)
ALTER TABLE fsfile DROP COLUMN IF EXISTS fts_vector CASCADE;
ALTER TABLE content_chunks DROP COLUMN IF EXISTS fts_vector CASCADE;

-- Step 5: Add new GENERATED ALWAYS AS columns

-- FSFile: Weighted search with 250KB body_text limit to stay within 1MB tsvector limit
ALTER TABLE fsfile ADD COLUMN fts_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(author, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(subject, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(keywords, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(substring(body_text, 1, 250000), '')), 'C') ||
    setweight(to_tsvector('english', coalesce(label, '')), 'D')
) STORED;

-- ContentChunks: Simple text-only search
ALTER TABLE content_chunks ADD COLUMN fts_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(text, ''))
) STORED;

-- Step 6: Recreate GIN indexes
CREATE INDEX IF NOT EXISTS idx_fsfile_fts ON fsfile USING GIN(fts_vector);
CREATE INDEX IF NOT EXISTS idx_content_chunks_fts ON content_chunks USING GIN(fts_vector);

-- Step 7: Update search function to work with new GENERATED columns
-- (The search_full_text function should work unchanged, but recreate for safety)

DROP FUNCTION IF EXISTS search_full_text(TEXT, INTEGER, INTEGER);

CREATE OR REPLACE FUNCTION search_full_text(
    search_query TEXT,
    limit_results INTEGER DEFAULT 20,
    offset_results INTEGER DEFAULT 0
) RETURNS TABLE (
    source_table TEXT,
    id BIGINT,
    uri TEXT,
    label TEXT,
    snippet TEXT,
    rank REAL
) AS $$
BEGIN
    RETURN QUERY
    -- Search in fsfile
    SELECT
        'fsfile'::TEXT as source_table,
        f.id,
        f.uri,
        f.label,
        ts_headline('english', COALESCE(f.body_text, f.label),
                    to_tsquery('english', search_query),
                    'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
        ts_rank(f.fts_vector, to_tsquery('english', search_query)) as rank
    FROM fsfile f
    WHERE f.fts_vector @@ to_tsquery('english', search_query)

    UNION ALL

    -- Search in content_chunks
    SELECT
        'content_chunks'::TEXT as source_table,
        c.id,
        COALESCE(f.uri, 'unknown') as uri,
        COALESCE(f.label, 'Chunk #' || c.chunk_number) as label,
        ts_headline('english', COALESCE(c.text, ''),
                    to_tsquery('english', search_query),
                    'MaxWords=50, MinWords=20, MaxFragments=1') as snippet,
        ts_rank(c.fts_vector, to_tsquery('english', search_query)) as rank
    FROM content_chunks c
    LEFT JOIN fsfile f ON c.concept_id = f.id
    WHERE c.fts_vector @@ to_tsquery('english', search_query)

    ORDER BY rank DESC
    LIMIT limit_results
    OFFSET offset_results;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION search_full_text IS 'Full-text search across fsfile and content_chunks with ranking (GENERATED columns version)';
COMMENT ON COLUMN fsfile.fts_vector IS 'Auto-generated FTS vector with weighted fields (A: title, B: metadata, C: body first 250KB, D: label)';
COMMENT ON COLUMN content_chunks.fts_vector IS 'Auto-generated FTS vector for chunk text';

-- Step 8: Recreate materialized view for search statistics
CREATE MATERIALIZED VIEW search_stats AS
SELECT
    'fsfile' as table_name,
    COUNT(*) as total_documents,
    COUNT(fts_vector) as indexed_documents,
    SUM(LENGTH(body_text)) as total_text_bytes
FROM fsfile
UNION ALL
SELECT
    'content_chunks' as table_name,
    COUNT(*) as total_documents,
    COUNT(fts_vector) as indexed_documents,
    SUM(LENGTH(text)) as total_text_bytes
FROM content_chunks;

-- Create unique index on the view
CREATE UNIQUE INDEX idx_search_stats_table ON search_stats(table_name);

-- Migration complete!
-- The fts_vector columns are now automatically maintained by PostgreSQL.
-- No triggers needed - the GENERATED ALWAYS AS ensures they update automatically.
