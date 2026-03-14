-- =============================================================================
-- essential-postgres-features.sql
-- =============================================================================
-- PostgreSQL-specific features that JPA ddl-auto cannot create.
-- Run this AFTER the application starts with ddl-auto: update.
--
-- Usage:
--   1. Drop and recreate database (fresh start):
--      PGPASSWORD=password psql -h minti9 -U postgres -c "DROP DATABASE IF EXISTS tempo;"
--      PGPASSWORD=password psql -h minti9 -U postgres -c "CREATE DATABASE tempo OWNER tempo;"
--
--   2. Start application (creates schema via JPA):
--      ./gradlew bootRun
--
--   3. Apply this file (PostgreSQL-specific features):
--      PGPASSWORD=password psql -h minti9 -U tempo -d tempo -f docs/sql/essential-postgres-features.sql
--
-- Contents:
--   1. pgvector extension
--   2. Full-text search (GENERATED columns + GIN indexes)
--   3. Vector similarity search (HNSW index)
--   4. Custom search functions
--   5. Performance indexes
--   6. Materialized views
--   7. CHECK constraints for enums
--   8. Seed data (optional)
--
-- =============================================================================

\set ON_ERROR_STOP on
\echo '=== Applying PostgreSQL-specific features ==='

-- =============================================================================
-- 1. PGVECTOR EXTENSION
-- =============================================================================
\echo '>>> Enabling pgvector extension...'

CREATE EXTENSION IF NOT EXISTS vector;

-- =============================================================================
-- 2. FULL-TEXT SEARCH: GENERATED COLUMNS
-- =============================================================================
-- JPA creates fts_vector as a regular bytea column. We need to replace it with
-- PostgreSQL GENERATED ALWAYS AS tsvector columns for automatic FTS indexing.
-- =============================================================================
\echo '>>> Setting up FTS GENERATED columns...'

-- FSFile: Drop JPA-created column and recreate as GENERATED
ALTER TABLE fsfile DROP COLUMN IF EXISTS fts_vector CASCADE;

ALTER TABLE fsfile ADD COLUMN fts_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(author, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(subject, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(keywords, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(substring(body_text, 1, 250000), '')), 'C') ||
    setweight(to_tsvector('english', coalesce(label, '')), 'D')
) STORED;

COMMENT ON COLUMN fsfile.fts_vector IS 'Auto-generated FTS vector with weighted fields (A: title, B: metadata, C: body first 250KB, D: label)';

-- ContentChunks: Drop JPA-created column and recreate with NLP-enhanced fields
ALTER TABLE content_chunks DROP COLUMN IF EXISTS fts_vector CASCADE;

ALTER TABLE content_chunks ADD COLUMN fts_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(replace(nouns, ',', ' '), '')), 'A') ||
    setweight(to_tsvector('english', coalesce(replace(verbs, ',', ' '), '')), 'B') ||
    setweight(to_tsvector('english', coalesce(text, '')), 'C')
) STORED;

COMMENT ON COLUMN content_chunks.fts_vector IS 'Auto-generated FTS vector with NLP-enhanced fields (A: nouns, B: verbs, C: text)';

-- =============================================================================
-- 3. FULL-TEXT SEARCH: GIN INDEXES
-- =============================================================================
\echo '>>> Creating FTS GIN indexes...'

CREATE INDEX IF NOT EXISTS idx_fsfile_fts ON fsfile USING GIN(fts_vector);
CREATE INDEX IF NOT EXISTS idx_content_chunks_fts ON content_chunks USING GIN(fts_vector);

-- NLP field indexes
CREATE INDEX IF NOT EXISTS idx_content_chunks_sentiment ON content_chunks(sentiment);
CREATE INDEX IF NOT EXISTS idx_content_chunks_nlp_processed ON content_chunks(nlp_processed_at);

-- =============================================================================
-- 4. VECTOR SIMILARITY: HNSW INDEX
-- =============================================================================
-- HNSW (Hierarchical Navigable Small World) provides fast approximate nearest
-- neighbor search for semantic/embedding queries.
-- =============================================================================
\echo '>>> Creating HNSW vector index...'

-- Drop existing index if present (in case dimensions changed)
DROP INDEX IF EXISTS content_chunks_embedding_hnsw_idx;

CREATE INDEX IF NOT EXISTS content_chunks_embedding_hnsw_idx
    ON content_chunks
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Partial index for efficiency (only index rows with embeddings)
CREATE INDEX IF NOT EXISTS content_chunks_embedding_not_null_idx
    ON content_chunks (id)
    WHERE embedding IS NOT NULL;

-- =============================================================================
-- 5. CUSTOM SEARCH FUNCTIONS
-- =============================================================================
\echo '>>> Creating search functions...'

-- Full-text search across fsfile and content_chunks
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

COMMENT ON FUNCTION search_full_text IS 'Full-text search across fsfile and content_chunks with ranking';

-- Sentiment-filtered search for content chunks
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

-- =============================================================================
-- 6. PERFORMANCE INDEXES
-- =============================================================================
\echo '>>> Creating performance indexes...'

-- Job run lookups (for file/folder browsing by crawl config)
CREATE INDEX IF NOT EXISTS idx_fsfile_job_run_id ON fsfile(job_run_id);
CREATE INDEX IF NOT EXISTS idx_fsfolder_job_run_id ON fsfolder(job_run_id);
CREATE INDEX IF NOT EXISTS idx_job_run_crawl_config_id ON job_run(crawl_config_id);

-- Discovery observation indexes
CREATE INDEX IF NOT EXISTS idx_discovery_rule_enabled
    ON discovery_classification_rule(enabled)
    WHERE EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'discovery_classification_rule');

CREATE INDEX IF NOT EXISTS idx_discovery_rule_group
    ON discovery_classification_rule(rule_group)
    WHERE EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'discovery_classification_rule');

-- =============================================================================
-- 7. MATERIALIZED VIEW: SEARCH STATISTICS
-- =============================================================================
\echo '>>> Creating search statistics view...'

DROP MATERIALIZED VIEW IF EXISTS search_stats CASCADE;

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

CREATE UNIQUE INDEX IF NOT EXISTS idx_search_stats_table ON search_stats(table_name);

-- Refresh function for the materialized view
CREATE OR REPLACE FUNCTION refresh_search_stats()
RETURNS void AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY search_stats;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- 8. CHECK CONSTRAINTS FOR ENUMS
-- =============================================================================
-- JPA @Enumerated creates VARCHAR columns but not CHECK constraints.
-- These ensure database-level validation of enum values.
-- =============================================================================
\echo '>>> Adding CHECK constraints...'

-- FSFolder analysis_status
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fsfolder_analysis_status_check'
    ) THEN
        ALTER TABLE fsfolder
            ADD CONSTRAINT fsfolder_analysis_status_check
            CHECK (analysis_status IN ('SKIP', 'LOCATE', 'INDEX', 'ANALYZE', 'SEMANTIC'));
    END IF;
END $$;

-- FSFile analysis_status
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fsfile_analysis_status_check'
    ) THEN
        ALTER TABLE fsfile
            ADD CONSTRAINT fsfile_analysis_status_check
            CHECK (analysis_status IN ('SKIP', 'LOCATE', 'INDEX', 'ANALYZE', 'SEMANTIC'));
    END IF;
END $$;

-- CrawlConfig crawl_mode (if column exists)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'crawl_config' AND column_name = 'crawl_mode'
    ) AND NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'crawl_config_crawl_mode_check'
    ) THEN
        ALTER TABLE crawl_config
            ADD CONSTRAINT crawl_config_crawl_mode_check
            CHECK (crawl_mode IN ('ENFORCE', 'DISCOVERY'));
    END IF;
END $$;

-- DiscoveredFolder suggested_status (if table exists)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables WHERE table_name = 'discovered_folder'
    ) AND NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'discovered_folder_suggested_status_check'
    ) THEN
        ALTER TABLE discovered_folder
            ADD CONSTRAINT discovered_folder_suggested_status_check
            CHECK (
                suggested_status IS NULL
                OR suggested_status IN ('SKIP', 'LOCATE', 'INDEX', 'ANALYZE', 'SEMANTIC', 'UNKNOWN')
            );
    END IF;
END $$;

-- CrawlDiscoveryFolderObs detected_folder_type (if table exists)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables WHERE table_name = 'crawl_discovery_folder_obs'
    ) AND NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'crawl_discovery_folder_obs_type_check'
    ) THEN
        ALTER TABLE crawl_discovery_folder_obs
            ADD CONSTRAINT crawl_discovery_folder_obs_type_check
            CHECK (detected_folder_type IS NULL OR detected_folder_type IN (
                'SOURCE_CODE', 'DOCUMENTATION', 'OFFICE_DOCS', 'MEDIA',
                'DATA', 'CONFIG', 'BUILD_ARTIFACTS', 'MIXED', 'UNKNOWN'
            ));
    END IF;
END $$;

-- =============================================================================
-- 9. SEED DATA (Optional)
-- =============================================================================
-- Uncomment to include seed data on fresh installs.
-- =============================================================================

-- \echo '>>> Applying seed data...'
-- \ir archive/012-seed-crawl-configs.sql
-- \ir archive/034-seed-user-ownership.sql

-- =============================================================================
-- DONE
-- =============================================================================
\echo ''
\echo '=== PostgreSQL features applied successfully ==='
\echo ''
\echo 'Next steps:'
\echo '  - Optionally run seed data: psql -f docs/sql/archive/012-seed-crawl-configs.sql'
\echo '  - Refresh search stats: SELECT refresh_search_stats();'
\echo ''
