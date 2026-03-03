-- 027: Crawl simplification - Add discovery and progressive analysis tracking fields.
-- Supports the decoupled Discovery -> Assignment -> Progressive Analysis pipeline.

-- SaveableObject fields (applies to fs_file, fs_folder, and any other SaveableObject subclasses)

-- located_at: When filesystem metadata was last synced (discovery time)
ALTER TABLE fs_file ADD COLUMN IF NOT EXISTS located_at TIMESTAMPTZ;
ALTER TABLE fs_folder ADD COLUMN IF NOT EXISTS located_at TIMESTAMPTZ;

-- skip_detected: True if SKIP pattern matched during discovery (before full pattern assignment)
ALTER TABLE fs_file ADD COLUMN IF NOT EXISTS skip_detected BOOLEAN;
ALTER TABLE fs_folder ADD COLUMN IF NOT EXISTS skip_detected BOOLEAN;

-- analysis_status_reason: Explains why this status was assigned
-- Examples: "PATTERN: .*\.git.*", "MANUAL: User request", "INHERITED: parent folder"
ALTER TABLE fs_file ADD COLUMN IF NOT EXISTS analysis_status_reason TEXT;
ALTER TABLE fs_folder ADD COLUMN IF NOT EXISTS analysis_status_reason TEXT;

-- analysis_status_set_by: Who/what set the analysis status
-- Values: PATTERN, MANUAL, INHERITED, DEFAULT
ALTER TABLE fs_file ADD COLUMN IF NOT EXISTS analysis_status_set_by TEXT;
ALTER TABLE fs_folder ADD COLUMN IF NOT EXISTS analysis_status_set_by TEXT;

-- FSFile-specific fields for progressive analysis tracking

-- indexed_at: When Tika text extraction was performed
ALTER TABLE fs_file ADD COLUMN IF NOT EXISTS indexed_at TIMESTAMPTZ;

-- index_error: Stores Tika extraction error message if failed
ALTER TABLE fs_file ADD COLUMN IF NOT EXISTS index_error TEXT;

-- archive_contents: JSON metadata for archive files (zip, tar, etc.)
-- Stores entry names/sizes without extracting full contents
-- Example: [{"name": "file1.txt", "size": 1234}, {"name": "dir/file2.txt", "size": 5678}]
ALTER TABLE fs_file ADD COLUMN IF NOT EXISTS archive_contents JSONB;

-- Indexes for progressive analysis queries
CREATE INDEX IF NOT EXISTS idx_fs_file_located_at ON fs_file (located_at);
CREATE INDEX IF NOT EXISTS idx_fs_file_indexed_at ON fs_file (indexed_at);
CREATE INDEX IF NOT EXISTS idx_fs_file_skip_detected ON fs_file (skip_detected) WHERE skip_detected = true;
CREATE INDEX IF NOT EXISTS idx_fs_folder_skip_detected ON fs_folder (skip_detected) WHERE skip_detected = true;

-- Index for finding items needing analysis assignment
CREATE INDEX IF NOT EXISTS idx_fs_file_analysis_status_set_by ON fs_file (analysis_status_set_by);
CREATE INDEX IF NOT EXISTS idx_fs_folder_analysis_status_set_by ON fs_folder (analysis_status_set_by);

-- Partial index for progressive analysis: files needing indexing
-- (analysis_status >= INDEX and not yet indexed)
CREATE INDEX IF NOT EXISTS idx_fs_file_needs_indexing
    ON fs_file (analysis_status, indexed_at)
    WHERE analysis_status IN ('INDEX', 'ANALYZE', 'SEMANTIC') AND indexed_at IS NULL;

-- Partial index for progressive analysis: files needing chunking
-- (has body_text but not yet chunked, or chunked before last update)
CREATE INDEX IF NOT EXISTS idx_fs_file_needs_chunking
    ON fs_file (chunked_at, last_updated)
    WHERE body_text IS NOT NULL AND (chunked_at IS NULL OR chunked_at < last_updated);
