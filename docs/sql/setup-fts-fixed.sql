-- PostgreSQL Full-Text Search Setup for Spring Search Tempo
-- Corrected for actual table names (fsfile, not fs_file)

-- 1. Add tsvector columns for full-text search

-- For fsfile table: add fts_vector column for body_text
ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS fts_vector tsvector;

-- For content_chunks table: convert fts_vector from VARCHAR to tsvector if needed
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'content_chunks'
        AND column_name = 'fts_vector'
        AND data_type != 'tsvector'
    ) THEN
        ALTER TABLE content_chunks DROP COLUMN fts_vector;
        ALTER TABLE content_chunks ADD COLUMN fts_vector tsvector;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'content_chunks'
        AND column_name = 'fts_vector'
    ) THEN
        ALTER TABLE content_chunks ADD COLUMN fts_vector tsvector;
    END IF;
END $$;

-- 2. Create GIN indexes for fast full-text search
CREATE INDEX IF NOT EXISTS idx_fsfile_fts ON fsfile USING GIN(fts_vector);
CREATE INDEX IF NOT EXISTS idx_content_chunks_fts ON content_chunks USING GIN(fts_vector);

-- 3. Create functions to update tsvector columns automatically

-- Function to update fsfile.fts_vector
CREATE OR REPLACE FUNCTION fsfile_fts_trigger() RETURNS trigger AS $$
BEGIN
    NEW.fts_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.author, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.subject, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.keywords, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.body_text, '')), 'C') ||
        setweight(to_tsvector('english', COALESCE(NEW.label, '')), 'D');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to update content_chunks.fts_vector
CREATE OR REPLACE FUNCTION content_chunks_fts_trigger() RETURNS trigger AS $$
BEGIN
    NEW.fts_vector := to_tsvector('english', COALESCE(NEW.text, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 4. Create triggers to keep tsvector columns up to date

DROP TRIGGER IF EXISTS fsfile_fts_update ON fsfile;
CREATE TRIGGER fsfile_fts_update
    BEFORE INSERT OR UPDATE ON fsfile
    FOR EACH ROW EXECUTE FUNCTION fsfile_fts_trigger();

DROP TRIGGER IF EXISTS content_chunks_fts_update ON content_chunks;
CREATE TRIGGER content_chunks_fts_update
    BEFORE INSERT OR UPDATE ON content_chunks
    FOR EACH ROW EXECUTE FUNCTION content_chunks_fts_trigger();

-- 5. Initialize fts_vector for existing rows

-- Update fsfile existing rows
UPDATE fsfile
SET fts_vector =
    setweight(to_tsvector('english', COALESCE(title, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(author, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(subject, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(keywords, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(body_text, '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(label, '')), 'D')
WHERE fts_vector IS NULL OR body_text IS NOT NULL;

-- Update content_chunks existing rows
UPDATE content_chunks
SET fts_vector = to_tsvector('english', COALESCE(text, ''))
WHERE fts_vector IS NULL OR text IS NOT NULL;

-- 6. Create a materialized view for search statistics (optional, for analytics)
DROP MATERIALIZED VIEW IF EXISTS search_stats;
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

-- Create index on the view
CREATE UNIQUE INDEX IF NOT EXISTS idx_search_stats_table ON search_stats(table_name);

-- 7. Create a search function with ranking
-- This function searches both fsfile and content_chunks and returns ranked results
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

COMMENT ON FUNCTION search_full_text IS 'Full-text search across fsfile and content_chunks with ranking';
COMMENT ON COLUMN fsfile.fts_vector IS 'Full-text search vector with weighted fields (A: title, B: author/subject/keywords, C: body, D: label)';
COMMENT ON COLUMN content_chunks.fts_vector IS 'Full-text search vector for chunk text';

-- Done! The database now has full-text search capabilities.
-- Use it like: SELECT * FROM search_full_text('search & terms', 20, 0);
