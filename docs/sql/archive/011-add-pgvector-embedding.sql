-- Migration: Add pgvector embedding column to content_chunks
-- Model: mxbai-embed-large (1024 dimensions)
-- Run this AFTER enabling pgvector extension

-- Step 1: Enable pgvector extension (requires superuser or extension privileges)
CREATE EXTENSION IF NOT EXISTS vector;

-- Step 2: Drop the old vectorEmbedding column (was String placeholder)
ALTER TABLE content_chunks DROP COLUMN IF EXISTS vector_embedding;

-- Step 3: Add new vector column with proper type
-- Using 1024 dimensions for mxbai-embed-large
ALTER TABLE content_chunks ADD COLUMN embedding vector(1024);

-- Step 4: Add timestamp for tracking when embedding was generated
ALTER TABLE content_chunks ADD COLUMN IF NOT EXISTS embedding_generated_at TIMESTAMP WITH TIME ZONE;

-- Step 5: Add embedding model name for tracking
ALTER TABLE content_chunks ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);

-- Step 6: Create HNSW index for fast approximate nearest neighbor search
-- HNSW is preferred over IVFFlat for better recall at similar speed
-- Using cosine distance (vector_cosine_ops) as it's standard for text embeddings
CREATE INDEX IF NOT EXISTS content_chunks_embedding_hnsw_idx
    ON content_chunks
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Step 7: Add partial index to avoid indexing NULL embeddings
-- This is more efficient than indexing all rows when many lack embeddings
CREATE INDEX IF NOT EXISTS content_chunks_embedding_not_null_idx
    ON content_chunks (id)
    WHERE embedding IS NOT NULL;

-- Example queries for semantic search:
--
-- Find similar chunks (cosine distance, lower is more similar):
-- SELECT id, text, embedding <=> '[0.1, 0.2, ...]'::vector AS distance
-- FROM content_chunks
-- WHERE embedding IS NOT NULL
-- ORDER BY embedding <=> '[0.1, 0.2, ...]'::vector
-- LIMIT 10;
--
-- Find chunks within similarity threshold:
-- SELECT id, text, 1 - (embedding <=> query_embedding) AS similarity
-- FROM content_chunks
-- WHERE embedding IS NOT NULL
--   AND embedding <=> query_embedding < 0.3  -- cosine distance threshold
-- ORDER BY embedding <=> query_embedding
-- LIMIT 20;
