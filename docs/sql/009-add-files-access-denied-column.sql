-- Migration: Add files_access_denied column to job_run table
-- This column tracks files that couldn't be read due to permission issues.
-- This is informational, not an error - expected when crawling system directories.

ALTER TABLE job_run
ADD COLUMN IF NOT EXISTS files_access_denied BIGINT DEFAULT 0;

COMMENT ON COLUMN job_run.files_access_denied IS 'Count of files with permission denied during extraction. Informational, not an error.';
