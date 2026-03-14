-- Add chunked_at column to fsfile table
-- This column tracks when a file was last chunked into ContentChunks.
-- Used by ChunkReader to skip already-chunked files (prevents reprocessing on every startup).

ALTER TABLE fsfile ADD COLUMN IF NOT EXISTS chunked_at TIMESTAMP WITH TIME ZONE;

-- Create index for efficient querying of files needing chunking
-- Query: WHERE body_text IS NOT NULL AND (chunked_at IS NULL OR last_updated > chunked_at)
CREATE INDEX IF NOT EXISTS idx_fsfile_chunked_at ON fsfile(chunked_at);

-- Optional: Backfill chunked_at for files that already have content_chunks
-- This marks existing chunked files so they won't be reprocessed
-- Run this if you have existing chunks and want to avoid re-chunking:
--
-- UPDATE fsfile f
-- SET chunked_at = (
--     SELECT MAX(cc.date_created)
--     FROM content_chunks cc
--     WHERE cc.concept_id = f.id
-- )
-- WHERE f.id IN (
--     SELECT DISTINCT concept_id FROM content_chunks WHERE concept_id IS NOT NULL
-- );

-- Verify column was added
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'fsfile'
AND column_name = 'chunked_at';
